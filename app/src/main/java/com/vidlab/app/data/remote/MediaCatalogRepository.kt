package com.vidlab.app.data.remote

import android.content.Context
import com.vidlab.app.data.model.*
import com.vidlab.app.data.scraper.FilenameSanitizer
import com.vidlab.app.data.scraper.MetadataScraper
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class MediaCatalogRepository(
    private val context: Context,
    private val webDavClient: WebDavClient = WebDavClient(),
    private val scraper: MetadataScraper = MetadataScraper(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {
    private val cacheFile = File(context.filesDir, "catalog_cache.json")

    fun getCachedFeed(): List<MediaFeedSection>? {
        return try {
            if (cacheFile.exists()) {
                json.decodeFromString<List<MediaFeedSection>>(cacheFile.readText())
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun buildMediaCenterFeed(): Result<List<MediaFeedSection>> = withContext(Dispatchers.IO) {
        runCatching {
            val allVideoFiles = mutableListOf<MediaFile>()
            val queue = ArrayDeque<String>()
            queue.add("/")

            var iterations = 0
            while (queue.isNotEmpty() && iterations < 300) {
                iterations++
                val currentPath = queue.removeFirst()
                val entries = webDavClient.listDirectory(currentPath).getOrDefault(emptyList())
                for (entry in entries) {
                    if (entry.isDirectory) {
                        queue.add(entry.path)
                    } else if (entry.isVideo) {
                        allVideoFiles.add(entry)
                    }
                }
            }

            val scrapedItems = groupAndScrapeMedia(allVideoFiles)

            val animeList = mutableListOf<ScrapedMediaItem>()
            val tvList = mutableListOf<ScrapedMediaItem>()
            val movieList = mutableListOf<ScrapedMediaItem>()

            for (item in scrapedItems) {
                val isAnime = item.metadata?.genres?.any { it.equals("Animation", true) } == true ||
                              item.primaryFile.path.contains("anime", true)
                if (isAnime) {
                    animeList.add(item)
                } else if (item.isSeries) {
                    tvList.add(item)
                } else {
                    movieList.add(item)
                }
            }

            val sections = mutableListOf<MediaFeedSection>()
            if (animeList.isNotEmpty()) sections.add(MediaFeedSection("Anime", animeList))
            if (tvList.isNotEmpty()) sections.add(MediaFeedSection("TV Shows", tvList))
            if (movieList.isNotEmpty()) sections.add(MediaFeedSection("Movies", movieList))

            try {
                cacheFile.writeText(json.encodeToString(sections))
            } catch (_: Exception) {}

            sections
        }
    }

    private suspend fun groupAndScrapeMedia(files: List<MediaFile>): List<ScrapedMediaItem> = coroutineScope {
        val seriesBuckets = mutableMapOf<String, MutableList<Pair<MediaFile, Int>>>()
        val standaloneVideos = mutableListOf<MediaFile>()

        for (file in files) {
            val fileParsed = FilenameSanitizer.parse(file.name)
            val pathSegments = file.path.trim('/').split('/')

            if (fileParsed.isSeries) {
                var showTitle = fileParsed.cleanTitle
                if (pathSegments.size >= 2) {
                    val candidate = FilenameSanitizer.parse(pathSegments[pathSegments.size - 2])
                    if (!candidate.isSeries && candidate.cleanTitle.length > 2) {
                        showTitle = candidate.cleanTitle
                    } else if (pathSegments.size >= 3) {
                        val grandParent = FilenameSanitizer.parse(pathSegments[pathSegments.size - 3])
                        if (grandParent.cleanTitle.length > 2) showTitle = grandParent.cleanTitle
                    }
                }
                val key = showTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
                seriesBuckets.getOrPut(key) { mutableListOf() }.add(Pair(file, fileParsed.season))
            } else {
                standaloneVideos.add(file)
            }
        }

        val seriesDeferred = seriesBuckets.entries.map { (_, filePairs) ->
            async {
                val sampleFile = filePairs.first().first
                val (meta, scrapedEpisodesMap) = scraper.fetchDetailedMetadata(sampleFile.name)
                val cleanTitle = meta?.title ?: FilenameSanitizer.parse(sampleFile.name).cleanTitle

                val seasonGroups = filePairs.groupBy { it.second }.map { (sNum, pairs) ->
                    val sortedEps = pairs.map { it.first }.sortedBy { it.name }.mapIndexed { idx, epFile ->
                        val epParsed = FilenameSanitizer.parse(epFile.name)
                        val epNum = epParsed.episode ?: (idx + 1)
                        val key = "S${sNum}E${epNum}"
                        val scrapedEp = scrapedEpisodesMap[key]

                        ProcessedEpisode(
                            file = epFile,
                            seasonNumber = sNum,
                            episodeNumber = epNum,
                            title = scrapedEp?.title ?: "Episode $epNum",
                            thumbnail = scrapedEp?.thumbnail ?: meta?.backdropUrl ?: meta?.posterUrl
                        )
                    }
                    SeasonGroup(seasonNumber = sNum, episodes = sortedEps)
                }.sortedBy { it.seasonNumber }

                val firstPlayable = seasonGroups.firstOrNull()?.episodes?.firstOrNull()?.file ?: sampleFile

                ScrapedMediaItem(
                    id = cleanTitle,
                    title = cleanTitle,
                    posterUrl = meta?.posterUrl,
                    backdropUrl = meta?.backdropUrl ?: meta?.posterUrl,
                    releaseYear = meta?.releaseYear,
                    isSeries = true,
                    primaryFile = firstPlayable,
                    seasons = seasonGroups,
                    metadata = meta
                )
            }
        }

        val movieDeferred = standaloneVideos.map { file ->
            async {
                val (meta, _) = scraper.fetchDetailedMetadata(file.name)
                val cleanTitle = meta?.title ?: FilenameSanitizer.parse(file.name).cleanTitle

                ScrapedMediaItem(
                    id = file.path,
                    title = cleanTitle,
                    posterUrl = meta?.posterUrl,
                    backdropUrl = meta?.backdropUrl ?: meta?.posterUrl,
                    releaseYear = meta?.releaseYear,
                    isSeries = false,
                    primaryFile = file,
                    metadata = meta
                )
            }
        }

        (seriesDeferred + movieDeferred).awaitAll()
    }

    suspend fun getRawDirectory(path: String): Result<List<MediaFile>> = webDavClient.listDirectory(path)
}

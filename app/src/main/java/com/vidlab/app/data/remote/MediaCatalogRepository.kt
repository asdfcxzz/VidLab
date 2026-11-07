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
            // Recursive scan of all video files
            val allVideoFiles = mutableListOf<MediaFile>()
            val queue = ArrayDeque<String>()
            queue.add("/")

            var depth = 0
            while (queue.isNotEmpty() && depth < 100) {
                depth++
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
        // Group files by show title / canonical key
        val seriesBuckets = mutableMapOf<String, MutableList<Pair<MediaFile, Int>>>()
        val standaloneVideos = mutableListOf<MediaFile>()

        for (file in files) {
            // Check file name and parent folder names for series indicators
            val pathParts = file.path.split("/").filter { it.isNotBlank() }
            val fileParsed = FilenameSanitizer.parse(file.name)

            var detectedSeason = fileParsed.season
            var showName = fileParsed.cleanTitle
            var isSeries = fileParsed.isSeries

            // Check if enclosed in a Season X or Show Name folder
            if (pathParts.size >= 2) {
                val parentFolder = pathParts[pathParts.size - 2]
                val parentParsed = FilenameSanitizer.parse(parentFolder)
                if (parentParsed.isSeries || parentFolder.contains("season", true) || parentFolder.matches(Regex("(?i)s\\d+"))) {
                    isSeries = true
                    if (parentParsed.season > 1 || detectedSeason == 1) {
                        detectedSeason = parentParsed.season
                    }
                    if (pathParts.size >= 3) {
                        showName = FilenameSanitizer.parse(pathParts[pathParts.size - 3]).cleanTitle
                    }
                } else if (pathParts.size >= 2 && !fileParsed.isSeries) {
                    // Parent folder might be the show name
                    if (files.count { it.path.startsWith(file.path.substringBeforeLast('/')) } > 1) {
                        showName = parentParsed.cleanTitle
                        isSeries = true
                    }
                }
            }

            val canonicalKey = showName.lowercase().replace(Regex("[^a-z0-9]"), "")

            if (isSeries) {
                seriesBuckets.getOrPut(canonicalKey) { mutableListOf() }.add(Pair(file, detectedSeason))
            } else {
                standaloneVideos.add(file)
            }
        }

        val seriesDeferred = seriesBuckets.entries.map { (_, filePairs) ->
            async {
                val sampleFile = filePairs.first().first
                val (meta, scrapedEpisodesMap) = scraper.fetchDetailedMetadata(sampleFile.name)
                val cleanTitle = meta?.title ?: FilenameSanitizer.parse(sampleFile.name).cleanTitle

                // Group into seasons
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

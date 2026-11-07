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
                val content = cacheFile.readText()
                json.decodeFromString<List<MediaFeedSection>>(content)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun buildMediaCenterFeed(): Result<List<MediaFeedSection>> = withContext(Dispatchers.IO) {
        runCatching {
            val rootListing = webDavClient.listDirectory("/").getOrThrow()
            val allRawEntries = mutableListOf<MediaFile>()

            for (item in rootListing) {
                if (item.isDirectory) {
                    val sub = webDavClient.listDirectory(item.path).getOrDefault(emptyList())
                    allRawEntries.addAll(sub)
                } else if (item.isVideo) {
                    allRawEntries.add(item)
                }
            }

            val scrapedItems = processDirectoryEntries(allRawEntries)

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

    private suspend fun processDirectoryEntries(entries: List<MediaFile>): List<ScrapedMediaItem> = coroutineScope {
        val seriesBuckets = mutableMapOf<String, MutableList<Pair<MediaFile, Int>>>()
        val standaloneVideos = mutableListOf<MediaFile>()

        for (entry in entries) {
            if (entry.isDirectory) {
                val parsed = FilenameSanitizer.parse(entry.name)
                val key = parsed.canonicalKey.ifEmpty { entry.name.lowercase().replace(" ", "") }
                seriesBuckets.getOrPut(key) { mutableListOf() }.add(Pair(entry, parsed.season))
            } else if (entry.isVideo) {
                standaloneVideos.add(entry)
            }
        }

        val seriesDeferred = seriesBuckets.entries.map { (_, folderList) ->
            async {
                val baseFolder = folderList.first().first
                val (meta, scrapedEpisodesMap) = scraper.fetchDetailedMetadata(baseFolder.name)
                val cleanTitle = meta?.title ?: FilenameSanitizer.parse(baseFolder.name).cleanTitle

                val seasons = mutableListOf<SeasonGroup>()
                for ((folder, sNum) in folderList) {
                    val filesInside = webDavClient.listDirectory(folder.path).getOrDefault(emptyList())
                    val videoFiles = filesInside.filter { it.isVideo }.sortedBy { it.name }

                    val processedList = videoFiles.mapIndexed { idx, epFile ->
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

                    if (processedList.isNotEmpty()) {
                        seasons.add(SeasonGroup(seasonNumber = sNum, episodes = processedList))
                    }
                }

                seasons.sortBy { it.seasonNumber }
                val firstPlayable = seasons.firstOrNull()?.episodes?.firstOrNull()?.file ?: baseFolder

                ScrapedMediaItem(
                    id = baseFolder.path,
                    title = cleanTitle,
                    posterUrl = meta?.posterUrl,
                    backdropUrl = meta?.backdropUrl ?: meta?.posterUrl,
                    releaseYear = meta?.releaseYear,
                    isSeries = true,
                    primaryFile = firstPlayable,
                    seasons = seasons,
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

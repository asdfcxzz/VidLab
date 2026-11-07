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
    // Increment cache version to auto-invalidate the old broken cache
    private val cacheFile = File(context.filesDir, "catalog_cache_v3.json")

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
            while (queue.isNotEmpty() && iterations < 500) {
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
            if (animeList.isNotEmpty()) sections.add(MediaFeedSection("Anime", animeList.sortedBy { it.title }))
            if (tvList.isNotEmpty()) sections.add(MediaFeedSection("TV Shows", tvList.sortedBy { it.title }))
            if (movieList.isNotEmpty()) sections.add(MediaFeedSection("Movies", movieList.sortedBy { it.title }))

            try {
                // Remove legacy caches
                File(context.filesDir, "catalog_cache.json").delete()
                cacheFile.writeText(json.encodeToString(sections))
            } catch (_: Exception) {}

            sections
        }
    }

    private suspend fun groupAndScrapeMedia(files: List<MediaFile>): List<ScrapedMediaItem> = coroutineScope {
        // Map: ShowIdentifier -> MutableList of Episode Files
        val seriesBuckets = mutableMapOf<String, MutableList<MediaFile>>()
        val standaloneVideos = mutableListOf<MediaFile>()

        val seasonFolderPattern = Regex("""(?i)(?:season\s*(\d{1,2})|s(\d{1,2}))\b.*""")
        val episodePattern = Regex("""(?i)(?:s(\d{1,2})[ ._-]*e(\d{1,3})|(\d{1,2})x(\d{1,3})|\bep?\s*(\d{1,3})\b)""")

        for (file in files) {
            val segments = file.path.trim('/').split('/')
            val filename = file.name
            val hasEpPattern = episodePattern.containsMatchIn(filename)

            if (segments.size >= 2) {
                val parent = segments[segments.size - 2]
                val isParentSeason = seasonFolderPattern.matches(parent)

                val showName = if (isParentSeason && segments.size >= 3) {
                    segments[segments.size - 3]
                } else if (hasEpPattern) {
                    parent
                } else {
                    null
                }

                if (showName != null) {
                    val key = showName.lowercase().replace(Regex("[^a-z0-9]"), "")
                    seriesBuckets.getOrPut(key) { mutableListOf() }.add(file)
                    continue
                }
            }

            if (hasEpPattern) {
                val parsed = FilenameSanitizer.parse(filename)
                val key = parsed.cleanTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
                seriesBuckets.getOrPut(key) { mutableListOf() }.add(file)
            } else {
                standaloneVideos.add(file)
            }
        }

        val seriesDeferred = seriesBuckets.entries.map { (_, fileList) ->
            async {
                val sampleFile = fileList.first()
                val segments = sampleFile.path.trim('/').split('/')
                val rawShowName = if (segments.size >= 3 && seasonFolderPattern.matches(segments[segments.size - 2])) {
                    segments[segments.size - 3]
                } else if (segments.size >= 2) {
                    segments[segments.size - 2]
                } else {
                    FilenameSanitizer.parse(sampleFile.name).cleanTitle
                }

                val (meta, scrapedEpisodesMap) = scraper.fetchDetailedMetadata(rawShowName)
                val cleanTitle = meta?.title ?: FilenameSanitizer.parse(rawShowName).cleanTitle

                val seasonMap = mutableMapOf<Int, MutableList<ProcessedEpisode>>()

                for (epFile in fileList) {
                    val epParsed = FilenameSanitizer.parse(epFile.name)
                    var sNum = epParsed.season

                    // Verify season from parent folder if filename didn't specify season
                    val epSegments = epFile.path.trim('/').split('/')
                    if (epSegments.size >= 2) {
                        val folderMatch = seasonFolderPattern.find(epSegments[epSegments.size - 2])
                        if (folderMatch != null) {
                            val matchedS = folderMatch.groupValues[1].ifEmpty { folderMatch.groupValues[2] }.toIntOrNull()
                            if (matchedS != null) sNum = matchedS
                        }
                    }

                    val epNum = epParsed.episode ?: 1
                    val key = "S${sNum}E${epNum}"
                    val scrapedEp = scrapedEpisodesMap[key]

                    seasonMap.getOrPut(sNum) { mutableListOf() }.add(
                        ProcessedEpisode(
                            file = epFile,
                            seasonNumber = sNum,
                            episodeNumber = epNum,
                            title = scrapedEp?.title ?: "Episode $epNum",
                            thumbnail = scrapedEp?.thumbnail ?: meta?.backdropUrl ?: meta?.posterUrl
                        )
                    )
                }

                val seasonGroups = seasonMap.map { (sNum, eps) ->
                    SeasonGroup(seasonNumber = sNum, episodes = eps.sortedBy { it.episodeNumber })
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

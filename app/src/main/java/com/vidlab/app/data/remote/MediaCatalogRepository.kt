package com.vidlab.app.data.remote

import android.content.Context
import com.vidlab.app.data.model.*
import com.vidlab.app.data.scraper.FilenameSanitizer
import com.vidlab.app.data.scraper.MetadataScraper
import com.vidlab.app.data.scraper.ScrapedMetadata
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
    private val cacheFile = File(context.filesDir, "catalog_cache_v5.json")
    private val customMatchesFile = File(context.filesDir, "custom_matches.json")

    private fun getCustomMatches(): MutableMap<String, String> {
        return try {
            if (customMatchesFile.exists()) {
                val map: Map<String, String> = json.decodeFromString(customMatchesFile.readText())
                map.toMutableMap()
            } else mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun saveCustomMatch(originalKey: String, manualTitle: String) {
        val matches = getCustomMatches()
        matches[originalKey] = manualTitle
        customMatchesFile.writeText(json.encodeToString(matches))
        cacheFile.delete()
    }

    fun getCachedFeed(): List<MediaFeedSection>? {
        return try {
            if (cacheFile.exists()) {
                val list: List<MediaFeedSection> = json.decodeFromString(cacheFile.readText())
                list
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

            val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "flv")
            var iterations = 0

            while (queue.isNotEmpty() && iterations < 1500) {
                iterations++
                val currentPath = queue.removeFirst()
                val entries = webDavClient.listDirectory(currentPath).getOrDefault(emptyList())
                for (entry in entries) {
                    if (entry.isDirectory) {
                        queue.add(entry.path)
                    } else {
                        val ext = entry.name.substringAfterLast('.', "").lowercase()
                        if (ext in videoExtensions) {
                            allVideoFiles.add(entry)
                        }
                    }
                }
            }

            val scrapedItems = groupAndScrapeMedia(allVideoFiles)

            val animeList = mutableListOf<ScrapedMediaItem>()
            val tvList = mutableListOf<ScrapedMediaItem>()
            val movieList = mutableListOf<ScrapedMediaItem>()

            for (item in scrapedItems) {
                val isAnime = item.metadata?.genres?.any { it.contains("animation", true) } == true ||
                              item.primaryFile.path.contains("anime", true) ||
                              item.title.contains("isekai", true)

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
                cacheFile.writeText(json.encodeToString(sections))
            } catch (_: Exception) {}

            sections
        }
    }

    private suspend fun groupAndScrapeMedia(files: List<MediaFile>): List<ScrapedMediaItem> = coroutineScope {
        val customMatches = getCustomMatches()
        val seriesBuckets = mutableMapOf<String, MutableList<MediaFile>>()
        val standaloneVideos = mutableListOf<MediaFile>()

        val seasonFolderRegex = Regex("""(?i)^(?:season|s)\s*(\d{1,2})$""")
        val epPattern = Regex("""(?i)(?:s(\d{1,2})[ ._-]*e(\d{1,3})|(\d{1,2})x(\d{1,3})|\bep?\s*(\d{1,3})\b)""")

        for (file in files) {
            val parts = file.path.trim('/').split('/')
            val filename = file.name
            val hasEp = epPattern.containsMatchIn(filename)

            var detectedShowTitle: String? = null

            if (parts.size >= 3 && seasonFolderRegex.matches(parts[parts.size - 2])) {
                detectedShowTitle = parts[parts.size - 3]
            } else if (hasEp) {
                val sanitized = FilenameSanitizer.parse(filename)
                detectedShowTitle = if (sanitized.cleanTitle.length > 2 && sanitized.cleanTitle != "Episode") {
                    sanitized.cleanTitle
                } else if (parts.size >= 2) {
                    parts[parts.size - 2]
                } else {
                    filename.substringBeforeLast('.')
                }
            }

            if (detectedShowTitle != null) {
                val normKey = detectedShowTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
                seriesBuckets.getOrPut(normKey) { mutableListOf() }.add(file)
            } else {
                standaloneVideos.add(file)
            }
        }

        // Process Series
        val seriesDeferred = seriesBuckets.entries.map { (key, fileList) ->
            async {
                val sampleFile = fileList.first()
                val parts = sampleFile.path.trim('/').split('/')
                val rawTitle = if (parts.size >= 3 && seasonFolderRegex.matches(parts[parts.size - 2])) {
                    parts[parts.size - 3]
                } else {
                    FilenameSanitizer.parse(sampleFile.name).cleanTitle
                }

                val finalQuery = customMatches[key] ?: rawTitle
                val (scrapedMeta, scrapedEpisodesMap) = scraper.fetchDetailedMetadata(finalQuery)
                val cleanTitle = scrapedMeta?.title ?: finalQuery

                val appMetadata = scrapedMeta?.let {
                    MediaMetadata(
                        title = it.title,
                        overview = it.overview,
                        posterUrl = it.posterUrl,
                        backdropUrl = it.backdropUrl,
                        releaseYear = it.releaseYear,
                        genres = it.genres
                    )
                }

                val seasonMap = mutableMapOf<Int, MutableList<ProcessedEpisode>>()

                for (epFile in fileList) {
                    val epParts = epFile.path.trim('/').split('/')
                    var sNum = 1

                    if (epParts.size >= 3 && seasonFolderRegex.matches(epParts[epParts.size - 2])) {
                        sNum = seasonFolderRegex.find(epParts[epParts.size - 2])?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    } else {
                        val parsed = FilenameSanitizer.parse(epFile.name)
                        sNum = if (parsed.season > 0) parsed.season else 1
                    }

                    val epParsed = FilenameSanitizer.parse(epFile.name)
                    val epNum = epParsed.episode ?: 1
                    val epKey = "S${sNum}E${epNum}"
                    val scrapedEp = scrapedEpisodesMap[epKey]

                    seasonMap.getOrPut(sNum) { mutableListOf() }.add(
                        ProcessedEpisode(
                            file = epFile,
                            seasonNumber = sNum,
                            episodeNumber = epNum,
                            title = scrapedEp?.title ?: epFile.name.substringBeforeLast('.'),
                            thumbnail = scrapedEp?.thumbnail ?: scrapedMeta?.backdropUrl ?: scrapedMeta?.posterUrl
                        )
                    )
                }

                val seasonGroups = seasonMap.map { (sNum, eps) ->
                    SeasonGroup(seasonNumber = sNum, episodes = eps.sortedBy { it.episodeNumber })
                }.sortedBy { it.seasonNumber }

                val firstPlayable = seasonGroups.firstOrNull()?.episodes?.firstOrNull()?.file ?: sampleFile

                ScrapedMediaItem(
                    id = key,
                    title = cleanTitle,
                    posterUrl = scrapedMeta?.posterUrl,
                    backdropUrl = scrapedMeta?.backdropUrl ?: scrapedMeta?.posterUrl,
                    releaseYear = scrapedMeta?.releaseYear,
                    isSeries = true,
                    primaryFile = firstPlayable,
                    seasons = seasonGroups,
                    metadata = appMetadata
                )
            }
        }

        // Process Standalone Videos & Movies
        val movieDeferred = standaloneVideos.map { file ->
            async {
                val defaultTitle = FilenameSanitizer.parse(file.name).cleanTitle
                val normKey = defaultTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
                val searchTitle = customMatches[normKey] ?: defaultTitle

                val (scrapedMeta, _) = scraper.fetchDetailedMetadata(searchTitle)
                val cleanTitle = scrapedMeta?.title ?: defaultTitle

                val appMetadata = scrapedMeta?.let {
                    MediaMetadata(
                        title = it.title,
                        overview = it.overview,
                        posterUrl = it.posterUrl,
                        backdropUrl = it.backdropUrl,
                        releaseYear = it.releaseYear,
                        genres = it.genres
                    )
                }

                ScrapedMediaItem(
                    id = file.path,
                    title = cleanTitle,
                    posterUrl = scrapedMeta?.posterUrl,
                    backdropUrl = scrapedMeta?.backdropUrl ?: scrapedMeta?.posterUrl,
                    releaseYear = scrapedMeta?.releaseYear,
                    isSeries = false,
                    primaryFile = file,
                    metadata = appMetadata
                )
            }
        }

        (seriesDeferred + movieDeferred).awaitAll()
    }

    suspend fun getRawDirectory(path: String): Result<List<MediaFile>> = webDavClient.listDirectory(path)
}

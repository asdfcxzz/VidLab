package com.vidlab.app.data.remote

import android.content.Context
import com.vidlab.app.data.model.*
import com.vidlab.app.data.scraper.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLDecoder

class MediaCatalogRepository(
    private val context: Context,
    private val webDavClient: WebDavClient = WebDavClient(),
    val scraper: MetadataScraper = MetadataScraper(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {
    private val cacheFile = File(context.filesDir, "catalog_cache_v15.json")
    private val manualBindingsFile = File(context.filesDir, "manual_bindings.json")

    private val _ambiguousMatchesFlow = MutableSharedFlow<AmbiguousPrompt>(replay = 5)
    val ambiguousMatchesFlow: SharedFlow<AmbiguousPrompt> = _ambiguousMatchesFlow.asSharedFlow()

    private fun getManualBindings(): MutableMap<String, String> {
        return try {
            if (manualBindingsFile.exists()) {
                val map: Map<String, String> = json.decodeFromString(manualBindingsFile.readText())
                map.toMutableMap()
            } else mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun saveManualBinding(mediaKey: String, tmdbId: String, mediaType: String) {
        val bindings = getManualBindings()
        bindings[mediaKey] = "$tmdbId:$mediaType"
        manualBindingsFile.writeText(json.encodeToString(bindings))
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
            val visited = mutableSetOf<String>()

            while (queue.isNotEmpty()) {
                val currentPath = queue.removeFirst()
                val normalized = currentPath.trimEnd('/')
                if (normalized in visited) continue
                visited.add(normalized)

                val entries = webDavClient.listDirectory(currentPath).getOrDefault(emptyList())
                for (entry in entries) {
                    val decodedEntryPath = URLDecoder.decode(entry.path, "UTF-8").trimEnd('/')
                    if (entry.isDirectory) {
                        if (entry.path !in visited && decodedEntryPath !in visited) {
                            queue.add(entry.path)
                        }
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
        val manualBindings = getManualBindings()
        val seriesBuckets = mutableMapOf<String, MutableList<MediaFile>>()
        val standaloneVideos = mutableListOf<MediaFile>()

        for (file in files) {
            val decPath = URLDecoder.decode(file.path, "UTF-8")
            val parts = decPath.trim('/').split('/')
            val filename = file.name

            val fileParsed = FilenameSanitizer.parse(filename)
            var detectedShow: String? = null

            for (part in parts.reversed().drop(1)) {
                val p = FilenameSanitizer.parse(part)
                val lower = p.cleanTitle.lowercase()
                if (p.cleanTitle.length > 2 && lower !in setOf("shows", "tv shows", "tv", "anime", "movies", "media", "disk")) {
                    detectedShow = p.cleanTitle
                    break
                }
            }

            if (detectedShow == null && fileParsed.isSeries) {
                detectedShow = fileParsed.cleanTitle
            }

            if (detectedShow != null) {
                val key = detectedShow.lowercase().replace(Regex("[^a-z0-9]"), "")
                seriesBuckets.getOrPut(key) { mutableListOf() }.add(file)
            } else {
                standaloneVideos.add(file)
            }
        }

        // Process Series
        val seriesDeferred = seriesBuckets.entries.map { (key, fileList) ->
            async {
                val sampleFile = fileList.first()
                val decPath = URLDecoder.decode(sampleFile.path, "UTF-8")
                val parts = decPath.trim('/').split('/')

                var cleanShowName = FilenameSanitizer.parse(sampleFile.name).cleanTitle
                for (part in parts.reversed().drop(1)) {
                    val p = FilenameSanitizer.parse(part)
                    val lower = p.cleanTitle.lowercase()
                    if (p.cleanTitle.length > 2 && lower !in setOf("shows", "tv shows", "tv", "anime", "movies", "media", "disk")) {
                        cleanShowName = p.cleanTitle
                        break
                    }
                }

                val binding = manualBindings[key]
                val (forcedId, forcedType) = if (binding != null && binding.contains(":")) {
                    val sp = binding.split(":")
                    Pair(sp[0], sp[1])
                } else Pair(null, null)

                val matchResult = scraper.scrapeWithVerification(cleanShowName, forcedId, forcedType)

                var finalMeta: MediaMetadata? = null
                var finalEpisodesMap = emptyMap<String, ScrapedEpisode>()
                var needsReview = false

                when (matchResult) {
                    is MatchResult.ConfidentMatch -> {
                        finalMeta = matchResult.metadata
                        finalEpisodesMap = matchResult.episodes
                    }
                    is MatchResult.Ambiguous -> {
                        needsReview = true
                        _ambiguousMatchesFlow.emit(AmbiguousPrompt(key, cleanShowName, matchResult.candidates))
                    }
                    is MatchResult.NoMatch -> {
                        needsReview = true
                    }
                }

                val officialShowTitle = finalMeta?.title ?: cleanShowName
                val seasonMap = mutableMapOf<Int, MutableList<ProcessedEpisode>>()

                for (epFile in fileList) {
                    val dec = URLDecoder.decode(epFile.path, "UTF-8")
                    val epParts = dec.trim('/').split('/')
                    val epParsed = FilenameSanitizer.parse(epFile.name)

                    var sNum = epParsed.season
                    if (sNum == 1) {
                        for (part in epParts) {
                            val p = FilenameSanitizer.parse(part)
                            if (p.season > 1) {
                                sNum = p.season
                                break
                            }
                        }
                    }

                    val epNum = epParsed.episode ?: 1
                    val epKey = "S${sNum}E${epNum}"
                    val scrapedEp = finalEpisodesMap[epKey]

                    val formattedEp = String.format("S%02dE%02d", sNum, epNum)
                    val epName = if (!scrapedEp?.title.isNullOrBlank()) scrapedEp!!.title else "Episode $epNum"
                    val finalEpTitle = "$formattedEp • $epName"

                    seasonMap.getOrPut(sNum) { mutableListOf() }.add(
                        ProcessedEpisode(
                            file = epFile,
                            seasonNumber = sNum,
                            episodeNumber = epNum,
                            title = finalEpTitle,
                            thumbnail = scrapedEp?.thumbnail ?: finalMeta?.backdropUrl ?: finalMeta?.posterUrl
                        )
                    )
                }

                val seasonGroups = seasonMap.map { (sNum, eps) ->
                    SeasonGroup(seasonNumber = sNum, episodes = eps.sortedBy { it.episodeNumber })
                }.sortedBy { it.seasonNumber }

                val firstPlayable = seasonGroups.firstOrNull()?.episodes?.firstOrNull()?.file ?: sampleFile

                ScrapedMediaItem(
                    id = key,
                    title = officialShowTitle,
                    posterUrl = finalMeta?.posterUrl,
                    backdropUrl = finalMeta?.backdropUrl ?: finalMeta?.posterUrl,
                    releaseYear = finalMeta?.releaseYear,
                    isSeries = true,
                    primaryFile = firstPlayable,
                    seasons = seasonGroups,
                    metadata = finalMeta,
                    needsManualReview = needsReview
                )
            }
        }

        // Process Movies
        val movieDeferred = standaloneVideos.map { file ->
            async {
                val clean = FilenameSanitizer.parse(file.name).cleanTitle
                val key = clean.lowercase().replace(Regex("[^a-z0-9]"), "")

                val binding = manualBindings[key]
                val (forcedId, forcedType) = if (binding != null && binding.contains(":")) {
                    val sp = binding.split(":")
                    Pair(sp[0], sp[1])
                } else Pair(null, null)

                val matchResult = scraper.scrapeWithVerification(clean, forcedId, forcedType)

                var finalMeta: MediaMetadata? = null
                var needsReview = false

                when (matchResult) {
                    is MatchResult.ConfidentMatch -> finalMeta = matchResult.metadata
                    is MatchResult.Ambiguous -> {
                        needsReview = true
                        _ambiguousMatchesFlow.emit(AmbiguousPrompt(key, clean, matchResult.candidates))
                    }
                    is MatchResult.NoMatch -> needsReview = true
                }

                val officialMovieTitle = finalMeta?.title ?: clean

                ScrapedMediaItem(
                    id = key,
                    title = officialMovieTitle,
                    posterUrl = finalMeta?.posterUrl,
                    backdropUrl = finalMeta?.backdropUrl ?: finalMeta?.posterUrl,
                    releaseYear = finalMeta?.releaseYear,
                    isSeries = false,
                    primaryFile = file,
                    metadata = finalMeta,
                    needsManualReview = needsReview
                )
            }
        }

        (seriesDeferred + movieDeferred).awaitAll()
    }

    // Universal in-place rename: Keeps files in their EXACT existing folder, outputs .mkv
    fun generateRenamePlan(item: ScrapedMediaItem): List<RenameOperation> {
        val plan = mutableListOf<RenameOperation>()
        val cleanShowOrMovieTitle = sanitizeFsName(item.title)

        if (item.isSeries) {
            for (season in item.seasons) {
                for (ep in season.episodes) {
                    val decodedOldPath = URLDecoder.decode(ep.file.path, "UTF-8")
                    val parentDir = decodedOldPath.substringBeforeLast('/')
                    val epTitleClean = ep.title.substringAfter("•").trim().let { sanitizeFsName(it) }

                    val newFileName = if (epTitleClean.startsWith("Episode ", ignoreCase = true) || epTitleClean.isBlank()) {
                        String.format("%s - S%02dE%02d.mkv", cleanShowOrMovieTitle, season.seasonNumber, ep.episodeNumber)
                    } else {
                        String.format("%s - S%02dE%02d - %s.mkv", cleanShowOrMovieTitle, season.seasonNumber, ep.episodeNumber, epTitleClean)
                    }

                    val newFullPath = "$parentDir/$newFileName"
                    if (decodedOldPath != newFullPath) {
                        plan.add(RenameOperation(decodedOldPath, newFullPath, ep.file.name, newFileName))
                    }
                }
            }
        } else {
            val decodedOldPath = URLDecoder.decode(item.primaryFile.path, "UTF-8")
            val parentDir = decodedOldPath.substringBeforeLast('/')
            val yearSuffix = if (!item.releaseYear.isNullOrBlank()) " (${item.releaseYear})" else ""
            val newFileName = "$cleanShowOrMovieTitle$yearSuffix.mkv"
            val newFullPath = "$parentDir/$newFileName"

            if (decodedOldPath != newFullPath) {
                plan.add(RenameOperation(decodedOldPath, newFullPath, item.primaryFile.name, newFileName))
            }
        }
        return plan
    }

    suspend fun executeBatchRename(operations: List<RenameOperation>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            for (op in operations) {
                webDavClient.moveFile(op.oldPath, op.newPath).getOrThrow()
            }
            cacheFile.delete()
            Unit
        }
    }

    private fun sanitizeFsName(name: String): String {
        return name.replace(Regex("""[/\\?%*:|"<>]"""), "").trim()
    }

    suspend fun getRawDirectory(path: String): Result<List<MediaFile>> = webDavClient.listDirectory(path)
}

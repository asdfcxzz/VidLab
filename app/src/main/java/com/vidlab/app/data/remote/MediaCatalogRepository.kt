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
    val webDavClient: WebDavClient = WebDavClient(),
    val scraper: MetadataScraper = MetadataScraper(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {
    private val cacheFile = File(context.filesDir, "catalog_cache_v20.json")
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

    fun saveManualBinding(mediaKey: String, id: String, mediaType: String) {
        val bindings = getManualBindings()
        bindings[mediaKey] = "$id:$mediaType"
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
                val norm = "/" + currentPath.trim('/')
                if (norm in visited) continue
                visited.add(norm)

                val entries = webDavClient.listDirectory(norm).getOrDefault(emptyList())
                for (entry in entries) {
                    val entryNorm = "/" + entry.path.trim('/')
                    if (entry.isDirectory) {
                        if (entryNorm !in visited) queue.add(entryNorm)
                    } else {
                        val ext = entry.name.substringAfterLast('.', "").lowercase()
                        if (ext in videoExtensions) {
                            allVideoFiles.add(entry.copy(path = entryNorm))
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

                if (isAnime) animeList.add(item)
                else if (item.isSeries) tvList.add(item)
                else movieList.add(item)
            }

            val sections = listOf(
                MediaFeedSection("Anime", animeList.sortedBy { it.title }),
                MediaFeedSection("TV Shows", tvList.sortedBy { it.title }),
                MediaFeedSection("Movies", movieList.sortedBy { it.title })
            ).filter { it.items.isNotEmpty() }

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
            val parts = file.path.trim('/').split('/')
            val fileParsed = FilenameSanitizer.parse(file.name)
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
                val parts = sampleFile.path.trim('/').split('/')

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

                when (matchResult) {
                    is MatchResult.ConfidentMatch -> {
                        finalMeta = matchResult.metadata
                        finalEpisodesMap = matchResult.episodes
                    }
                    is MatchResult.Ambiguous -> {
                        _ambiguousMatchesFlow.emit(AmbiguousPrompt(key, cleanShowName, matchResult.candidates))
                    }
                    else -> {}
                }

                val officialShowTitle = finalMeta?.title ?: cleanShowName
                val seasonMap = mutableMapOf<Int, MutableList<ProcessedEpisode>>()

                for (epFile in fileList) {
                    val epParts = epFile.path.trim('/').split('/')
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
                    val epTitle = if (!scrapedEp?.title.isNullOrBlank()) scrapedEp!!.title else "Episode $epNum"
                    val finalEpTitle = "$formattedEp • $epTitle"

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
                    metadata = finalMeta
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

                when (matchResult) {
                    is MatchResult.ConfidentMatch -> finalMeta = matchResult.metadata
                    is MatchResult.Ambiguous -> {
                        _ambiguousMatchesFlow.emit(AmbiguousPrompt(key, clean, matchResult.candidates))
                    }
                    else -> {}
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
                    metadata = finalMeta
                )
            }
        }

        (seriesDeferred + movieDeferred).awaitAll()
    }

    fun generateRenamePlan(item: ScrapedMediaItem): List<RenameOperation> {
        val plan = mutableListOf<RenameOperation>()
        val cleanTitle = sanitizeFsName(item.title)

        if (item.isSeries) {
            for (season in item.seasons) {
                val seasonFolderName = "$cleanTitle Season ${season.seasonNumber}"
                for (ep in season.episodes) {
                    val oldPath = ep.file.path

                    // Skip if already in the target format
                    if (FilenameSanitizer.isPathAlreadyStructured(oldPath)) {
                        continue
                    }

                    val parts = oldPath.trim('/').split('/')
                    val parentLibraryDir = when {
                        parts.size >= 3 -> "/" + parts.take(parts.size - 2).joinToString("/")
                        parts.size == 2 -> "/" + parts.first()
                        else -> ""
                    }

                    val epTitleClean = ep.title.substringAfter("•").trim().let { sanitizeFsName(it) }
                    val newFileName = if (epTitleClean.startsWith("Episode ", ignoreCase = true) || epTitleClean.isBlank()) {
                        String.format("%s - S%02dE%02d.mkv", cleanTitle, season.seasonNumber, ep.episodeNumber)
                    } else {
                        String.format("%s - S%02dE%02d - %s.mkv", cleanTitle, season.seasonNumber, ep.episodeNumber, epTitleClean)
                    }

                    val newFullPath = "$parentLibraryDir/$cleanTitle/$seasonFolderName/$newFileName"
                    if (oldPath != newFullPath) {
                        plan.add(RenameOperation(oldPath, newFullPath, ep.file.name, newFileName))
                    }
                }
            }
        } else {
            val oldPath = item.primaryFile.path

            if (FilenameSanitizer.isPathAlreadyStructured(oldPath)) {
                return emptyList()
            }

            val parts = oldPath.trim('/').split('/')
            val yearSuffix = if (!item.releaseYear.isNullOrBlank()) " (${item.releaseYear})" else ""
            val movieFolderName = "$cleanTitle$yearSuffix"
            val newFileName = "$cleanTitle$yearSuffix.mkv"

            val parentDir = if (parts.size > 1) "/" + parts.dropLast(1).joinToString("/") else ""
            val currentParentFolderName = if (parts.size > 1) parts[parts.size - 2] else ""

            val newFullPath = if (currentParentFolderName.equals(movieFolderName, ignoreCase = true)) {
                "$parentDir/$newFileName"
            } else {
                "$parentDir/$movieFolderName/$newFileName"
            }

            if (oldPath != newFullPath) {
                plan.add(RenameOperation(oldPath, newFullPath, item.primaryFile.name, newFileName))
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

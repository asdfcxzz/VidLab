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

class MediaCatalogRepository(
    private val context: Context,
    val webDavClient: WebDavClient = WebDavClient(),
    val scraper: MetadataScraper = MetadataScraper(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {
    private val cacheFile = File(context.filesDir, "catalog_cache_v23.json")
    private val manualBindingsFile = File(context.filesDir, "manual_bindings.json")

    private val _ambiguousMatchesFlow = MutableSharedFlow<AmbiguousPrompt>(replay = 10)
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

    fun saveManualBinding(mediaKey: String, targetTitleOrId: String, mediaType: String, year: String? = null) {
        val bindings = getManualBindings()
        val yearPart = if (!year.isNullOrBlank()) ":$year" else ""
        bindings[mediaKey] = "$targetTitleOrId:$mediaType$yearPart"
        manualBindingsFile.writeText(json.encodeToString(bindings))
        cacheFile.delete()
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

            listOf(
                MediaFeedSection("Anime", animeList.sortedBy { it.title }),
                MediaFeedSection("TV Shows", tvList.sortedBy { it.title }),
                MediaFeedSection("Movies", movieList.sortedBy { it.title })
            ).filter { it.items.isNotEmpty() }
        }
    }

    private suspend fun groupAndScrapeMedia(files: List<MediaFile>): List<ScrapedMediaItem> = coroutineScope {
        val manualBindings = getManualBindings()
        val seriesBuckets = mutableMapOf<String, MutableList<MediaFile>>()
        val standaloneVideos = mutableListOf<MediaFile>()

        val seasonFolderRegex = Regex("""(?i)\b(?:season|s)\s*0*(\d{1,2})\b""")

        for (file in files) {
            val parts = file.path.trim('/').split('/')
            val fileParsed = FilenameSanitizer.parse(file.name)
            var detectedKey: String? = null

            for (i in parts.indices.reversed()) {
                val seg = parts[i]
                if (seasonFolderRegex.containsMatchIn(seg) && i > 0) {
                    detectedKey = FilenameSanitizer.parse(parts[i - 1]).seriesKey
                    break
                }
            }

            if (detectedKey == null && fileParsed.isSeries) {
                detectedKey = fileParsed.seriesKey
            }

            if (detectedKey != null) {
                seriesBuckets.getOrPut(detectedKey) { mutableListOf() }.add(file)
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
                for (i in parts.indices.reversed()) {
                    if (seasonFolderRegex.containsMatchIn(parts[i]) && i > 0) {
                        cleanShowName = FilenameSanitizer.parse(parts[i - 1]).cleanTitle
                        break
                    }
                }

                val binding = manualBindings[key]
                var forcedType: String? = null
                if (binding != null) {
                    val sp = binding.split(":")
                    if (sp.isNotEmpty()) cleanShowName = sp[0]
                    if (sp.size >= 2) forcedType = sp[1]
                }

                val matchResult = scraper.scrapeWithVerification(cleanShowName, null, forcedType)
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
                    is MatchResult.NoMatch -> {
                        // Prompt manual input if not found in source database
                        if (binding == null) {
                            val fallbackSearch = scraper.searchMetadata(cleanShowName)
                            _ambiguousMatchesFlow.emit(AmbiguousPrompt(key, cleanShowName, fallbackSearch))
                        }
                    }
                }

                val officialShowTitle = finalMeta?.title ?: cleanShowName
                val seasonMap = mutableMapOf<Int, MutableList<ProcessedEpisode>>()
                val sortedFiles = fileList.sortedBy { it.name }

                for ((idx, epFile) in sortedFiles.withIndex()) {
                    val epParts = epFile.path.trim('/').split('/')
                    val epParsed = FilenameSanitizer.parse(epFile.name)

                    var sNum = epParsed.season
                    if (sNum == 1) {
                        for (part in epParts) {
                            val m = seasonFolderRegex.find(part)
                            if (m != null) {
                                sNum = m.groupValues[1].toIntOrNull() ?: 1
                                break
                            }
                        }
                    }

                    val epNum = if (epParsed.isAbsoluteEpisode && (epParsed.episode ?: 0) > 50) {
                        idx + 1
                    } else {
                        epParsed.episode ?: (idx + 1)
                    }

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

                var queryName = clean
                var forcedType: String? = null
                var forcedYear: String? = null

                val binding = manualBindings[key]
                if (binding != null) {
                    val sp = binding.split(":")
                    if (sp.isNotEmpty()) queryName = sp[0]
                    if (sp.size >= 2) forcedType = sp[1]
                    if (sp.size >= 3) forcedYear = sp[2]
                }

                val matchResult = scraper.scrapeWithVerification(queryName, null, forcedType)
                var finalMeta: MediaMetadata? = null

                when (matchResult) {
                    is MatchResult.ConfidentMatch -> finalMeta = matchResult.metadata
                    is MatchResult.Ambiguous -> {
                        _ambiguousMatchesFlow.emit(AmbiguousPrompt(key, queryName, matchResult.candidates))
                    }
                    is MatchResult.NoMatch -> {
                        // Prompt manual input if not found in source database
                        if (binding == null) {
                            val fallbackSearch = scraper.searchMetadata(queryName)
                            _ambiguousMatchesFlow.emit(AmbiguousPrompt(key, queryName, fallbackSearch))
                        }
                    }
                }

                val officialMovieTitle = finalMeta?.title ?: queryName

                ScrapedMediaItem(
                    id = key,
                    title = officialMovieTitle,
                    posterUrl = finalMeta?.posterUrl,
                    backdropUrl = finalMeta?.backdropUrl ?: finalMeta?.posterUrl,
                    releaseYear = forcedYear ?: finalMeta?.releaseYear,
                    isSeries = false,
                    primaryFile = file,
                    metadata = finalMeta
                )
            }
        }

        (seriesDeferred + movieDeferred).awaitAll()
    }

    // Skips files that are already correctly structured
    fun generateRenamePlan(item: ScrapedMediaItem, customTitle: String? = null, customYear: String? = null): List<RenameOperation> {
        val plan = mutableListOf<RenameOperation>()
        val cleanTitle = sanitizeFsName(customTitle ?: item.title)

        if (item.isSeries) {
            for (season in item.seasons) {
                val seasonFolderName = "$cleanTitle Season ${season.seasonNumber}"
                for (ep in season.episodes) {
                    val oldPath = ep.file.path

                    // SKIP IF ALREADY STRUCTURED
                    if (FilenameSanitizer.isSeriesAlreadyStructured(oldPath)) {
                        continue
                    }

                    val parts = oldPath.trim('/').split('/')
                    val rootSection = if (parts.isNotEmpty() && parts[0].uppercase() in setOf("ANIME", "SHOWS", "TV", "TV SHOWS")) {
                        "/" + parts[0]
                    } else ""

                    val epTitleClean = ep.title.substringAfter("•").trim().let { sanitizeFsName(it) }
                    val newFileName = if (epTitleClean.startsWith("Episode ", ignoreCase = true) || epTitleClean.isBlank()) {
                        String.format("%s - S%02dE%02d.mkv", cleanTitle, season.seasonNumber, ep.episodeNumber)
                    } else {
                        String.format("%s - S%02dE%02d - %s.mkv", cleanTitle, season.seasonNumber, ep.episodeNumber, epTitleClean)
                    }

                    val newFullPath = "$rootSection/$cleanTitle/$seasonFolderName/$newFileName"
                    if (oldPath != newFullPath) {
                        plan.add(RenameOperation(oldPath, newFullPath, ep.file.name, newFileName))
                    }
                }
            }
        } else {
            val oldPath = item.primaryFile.path

            // SKIP IF ALREADY STRUCTURED
            if (FilenameSanitizer.isMovieAlreadyStructured(oldPath)) {
                return emptyList()
            }

            val parts = oldPath.trim('/').split('/')
            val rootSection = if (parts.isNotEmpty() && parts[0].uppercase() in setOf("MOVIES", "FILMS")) {
                "/" + parts[0]
            } else if (parts.size > 1) {
                "/" + parts.first()
            } else ""

            val year = customYear ?: item.releaseYear
            val yearSuffix = if (!year.isNullOrBlank()) " ($year)" else ""
            val movieFolderName = "$cleanTitle$yearSuffix"
            val newFileName = "$cleanTitle$yearSuffix.mkv"

            val newFullPath = "$rootSection/$movieFolderName/$newFileName"
            if (oldPath != newFullPath) {
                plan.add(RenameOperation(oldPath, newFullPath, item.primaryFile.name, newFileName))
            }
        }
        return plan
    }

    private fun sanitizeFsName(name: String): String {
        return name.replace(Regex("""[/\\?%*:|"<>]"""), "").trim()
    }
}

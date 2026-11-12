package com.vidlab.app.data.remote

import android.content.Context
import com.vidlab.app.data.model.*
import com.vidlab.app.data.scraper.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredBinding(
    val title: String,
    val mediaType: String,
    val year: String? = null,
    val posterUrl: String? = null,
    val tmdbId: String? = null
)

class MediaCatalogRepository(
    private val context: Context,
    val webDavClient: WebDavClient = WebDavClient(),
    val scraper: MetadataScraper = MetadataScraper(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {
    private val cacheFile = File(context.filesDir, "catalog_cache_v240.json")
    private val manualBindingsFile = File(context.filesDir, "manual_bindings_v5.json")

    private val _ambiguousMatchesFlow = MutableSharedFlow<AmbiguousPrompt>(replay = 10)
    val ambiguousMatchesFlow: SharedFlow<AmbiguousPrompt> = _ambiguousMatchesFlow.asSharedFlow()

    private val ignoredCategoryFolders = setOf(
        "shows", "tv shows", "tv", "anime", "movies", "media", "disk", "root", "webdav", "videos", "extracted_stream", ""
    )

    init {
        try {
            context.filesDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("catalog_cache_") && f.name != "catalog_cache_v240.json") {
                    f.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun getManualBindings(): MutableMap<String, StoredBinding> {
        return try {
            if (manualBindingsFile.exists()) {
                val map: Map<String, StoredBinding> = json.decodeFromString(manualBindingsFile.readText())
                map.toMutableMap()
            } else mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun saveManualBinding(
        mediaKey: String,
        targetTitle: String,
        mediaType: String,
        year: String? = null,
        posterUrl: String? = null,
        tmdbId: String? = null
    ) {
        if (mediaKey.isBlank() || mediaKey in ignoredCategoryFolders) return
        val bindings = getManualBindings()
        val clean = targetTitle.replace(Regex("(?i)(tv|movie)\\d{4}"), "").trim()
        bindings[mediaKey] = StoredBinding(clean, mediaType, year, posterUrl, tmdbId)
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
                val pathUpper = item.primaryFile.path.uppercase()
                when {
                    pathUpper.contains("/MOVIES/") || !item.isSeries -> movieList.add(item)
                    pathUpper.contains("/ANIME/") -> animeList.add(item)
                    pathUpper.contains("/SHOWS/") -> tvList.add(item)
                    else -> {
                        val isAnimeGenre = item.metadata?.genres?.any { it.contains("animation", true) } == true
                        if (isAnimeGenre) animeList.add(item) else tvList.add(item)
                    }
                }
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
        val movieFiles = mutableListOf<MediaFile>()

        for (file in files) {
            val parts = file.path.trim('/').split('/')
            val inMoviesFolder = parts.any { it.equals("MOVIES", ignoreCase = true) }

            // Strict Separation: Everything in /MOVIES is treated as a standalone movie
            if (inMoviesFolder) {
                movieFiles.add(file)
                continue
            }

            // Everything in /ANIME and /SHOWS is treated as a series
            val fileParsed = FilenameSanitizer.parse(file.name, forceMovie = false)
            var detectedKey: String? = null

            if (fileParsed.cleanTitle.length > 2 && fileParsed.seriesKey !in ignoredCategoryFolders) {
                detectedKey = fileParsed.seriesKey
            }

            if (detectedKey == null) {
                for (seg in parts.reversed().drop(1)) {
                    val p = FilenameSanitizer.parse(seg, forceMovie = false)
                    if (p.cleanTitle.length > 2 && p.seriesKey !in ignoredCategoryFolders && !p.cleanTitle.startsWith("Season", ignoreCase = true)) {
                        detectedKey = p.seriesKey
                        break
                    }
                }
            }

            if (detectedKey != null && detectedKey !in ignoredCategoryFolders) {
                seriesBuckets.getOrPut(detectedKey) { mutableListOf() }.add(file)
            } else {
                movieFiles.add(file)
            }
        }

        // Process Series (ANIME & SHOWS)
        val seriesDeferred = seriesBuckets.entries.map { (key, fileList) ->
            async {
                val sampleFile = fileList.first()
                val sampleParsed = FilenameSanitizer.parse(sampleFile.name, forceMovie = false)

                var cleanShowName = if (sampleParsed.cleanTitle.length > 2) {
                    sampleParsed.cleanTitle
                } else {
                    val parts = sampleFile.path.trim('/').split('/')
                    var folderFallback = sampleFile.name.substringBeforeLast('.')
                    for (seg in parts.reversed().drop(1)) {
                        val p = FilenameSanitizer.parse(seg)
                        if (p.cleanTitle.length > 2 && p.seriesKey !in ignoredCategoryFolders && !p.cleanTitle.startsWith("Season", ignoreCase = true)) {
                            folderFallback = p.cleanTitle
                            break
                        }
                    }
                    folderFallback
                }

                cleanShowName = cleanShowName.replace(Regex("(?i)(tv|movie)\\d{4}"), "").trim()

                val binding = manualBindings[key]
                val forcedType = binding?.mediaType ?: "tv"
                val forcedYear = binding?.year
                val forcedPoster = binding?.posterUrl
                val forcedId = binding?.tmdbId
                if (binding != null) {
                    cleanShowName = binding.title
                }

                val matchResult = runCatching {
                    scraper.scrapeWithVerification(cleanShowName, true, forcedId, forcedType, forcedYear)
                }.getOrDefault(MatchResult.NoMatch)

                var finalMeta: MediaMetadata? = null
                var finalEpisodesMap = emptyMap<String, ScrapedEpisode>()

                when (matchResult) {
                    is MatchResult.ConfidentMatch -> {
                        finalMeta = matchResult.metadata
                        finalEpisodesMap = matchResult.episodes
                    }
                    is MatchResult.Ambiguous -> {
                        _ambiguousMatchesFlow.tryEmit(AmbiguousPrompt(key, cleanShowName, matchResult.candidates))
                    }
                    is MatchResult.NoMatch -> {
                        val fallbackCandidates = scraper.searchMetadata(cleanShowName)
                        _ambiguousMatchesFlow.tryEmit(AmbiguousPrompt(key, cleanShowName, fallbackCandidates))
                    }
                }

                if (forcedPoster != null && finalMeta == null) {
                    finalMeta = MediaMetadata(cleanShowName, forcedPoster, forcedPoster, forcedYear, listOf("TV Series"))
                }

                val officialShowTitle = finalMeta?.title ?: cleanShowName
                val seasonMap = mutableMapOf<Int, MutableList<ProcessedEpisode>>()
                val sortedFiles = fileList.sortedBy { it.name }
                val seasonFolderRegex = Regex("""(?i)\b(?:season|s)\s*0*(\d{1,2})\b""")

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

                    // Always prefer the official database episode title over anything in the local filename
                    val sTitle = scrapedEp?.title
                    val epTitle = when {
                        !sTitle.isNullOrBlank() && !sTitle.startsWith("Episode ", ignoreCase = true) -> sTitle
                        !epParsed.existingEpisodeTitle.isNullOrBlank() -> epParsed.existingEpisodeTitle
                        else -> "Episode $epNum"
                    }
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
                    releaseYear = forcedYear ?: finalMeta?.releaseYear,
                    isSeries = true,
                    primaryFile = firstPlayable,
                    seasons = seasonGroups,
                    metadata = finalMeta
                )
            }
        }

        // Process Movies (/MOVIES)
        val movieDeferred = movieFiles.map { file ->
            async {
                val parsed = FilenameSanitizer.parse(file.name, forceMovie = true)
                val key = parsed.seriesKey.ifBlank { file.name.hashCode().toString() }

                var queryName = parsed.cleanTitle.replace(Regex("(?i)(tv|movie)\\d{4}"), "").trim()
                val binding = manualBindings[key]
                val forcedType = "movie"
                val forcedYear = binding?.year ?: parsed.year
                val forcedPoster = binding?.posterUrl
                val forcedId = binding?.tmdbId
                if (binding != null) {
                    queryName = binding.title
                }

                val matchResult = runCatching {
                    scraper.scrapeWithVerification(queryName, false, forcedId, forcedType, forcedYear)
                }.getOrDefault(MatchResult.NoMatch)

                var finalMeta: MediaMetadata? = null

                when (matchResult) {
                    is MatchResult.ConfidentMatch -> finalMeta = matchResult.metadata
                    is MatchResult.Ambiguous -> {
                        _ambiguousMatchesFlow.tryEmit(AmbiguousPrompt(key, queryName, matchResult.candidates))
                    }
                    is MatchResult.NoMatch -> {
                        val fallbackCandidates = scraper.searchMetadata(queryName)
                        _ambiguousMatchesFlow.tryEmit(AmbiguousPrompt(key, queryName, fallbackCandidates))
                    }
                }

                if (forcedPoster != null && finalMeta == null) {
                    finalMeta = MediaMetadata(queryName, forcedPoster, forcedPoster, forcedYear, listOf("Movie"))
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

    // STRICT DATABASE NAMING CONTRACT:
    // Anime: /ANIME/{Database Title}/{Database Title Season X}/{Database Title - SxxExx - Database Episode Name.mkv}
    // Shows: /SHOWS/{Database Title}/{Database Title Season X}/{Database Title - SxxExx - Database Episode Name.mkv}
    // Movies: /MOVIES/{Database Movie Title (Year)}/{Database Movie Title (Year).mkv}
    fun generateRenamePlan(
        item: ScrapedMediaItem,
        customTitle: String? = null,
        customYear: String? = null,
        isManualOverride: Boolean = false
    ): List<RenameOperation> {
        val plan = mutableListOf<RenameOperation>()

        // If item failed to match the database and is not a manual override, skip auto-rename so user can resolve it manually
        if (item.metadata == null && !isManualOverride) {
            return emptyList()
        }

        var cleanTitle = sanitizeFsName(customTitle ?: item.metadata?.title ?: item.title)
        cleanTitle = cleanTitle.replace(Regex("(?i)(tv|movie)\\d{4}"), "").trim()

        if (cleanTitle.lowercase() in ignoredCategoryFolders || cleanTitle.isBlank()) {
            return emptyList()
        }

        if (item.isSeries) {
            for (season in item.seasons) {
                val seasonFolderName = "$cleanTitle Season ${season.seasonNumber}"

                for (ep in season.episodes) {
                    val oldPath = ep.file.path

                    val rootSection = if (oldPath.contains("/ANIME", ignoreCase = true)) {
                        "/ANIME"
                    } else {
                        "/SHOWS"
                    }

                    var epTitleClean = sanitizeFsName(ep.title.substringAfter("•").trim())
                    epTitleClean = epTitleClean.replace(Regex("[=+\\[\\]]"), "").replace(Regex("(?i)(tv|movie)\\d{4}"), "").trim()

                    val newFileName = if (epTitleClean.startsWith("Episode ", ignoreCase = true) || epTitleClean.isBlank()) {
                        String.format("%s - S%02dE%02d.mkv", cleanTitle, season.seasonNumber, ep.episodeNumber)
                    } else {
                        String.format("%s - S%02dE%02d - %s.mkv", cleanTitle, season.seasonNumber, ep.episodeNumber, epTitleClean)
                    }

                    // Strict 3-tier path: /CATEGORY/{Title}/{Title Season X}/{Title - SxxExx - EpName.mkv}
                    val newFullPath = "$rootSection/$cleanTitle/$seasonFolderName/$newFileName"

                    if (oldPath != newFullPath) {
                        plan.add(RenameOperation(oldPath, newFullPath, ep.file.name, newFileName))
                    }
                }
            }
        } else {
            val oldPath = item.primaryFile.path
            val year = customYear ?: item.metadata?.releaseYear ?: item.releaseYear
            val yearSuffix = if (!year.isNullOrBlank()) " ($year)" else ""
            val movieFolderName = "$cleanTitle$yearSuffix"
            val newFileName = "$cleanTitle$yearSuffix.mkv"

            val newFullPath = "/MOVIES/$movieFolderName/$newFileName"
            if (oldPath != newFullPath) {
                plan.add(RenameOperation(oldPath, newFullPath, item.primaryFile.name, newFileName))
            }
        }
        return plan
    }

    private fun sanitizeFsName(name: String): String {
        return name.replace(Regex("[/\\\\?%*:|\"<>=+]"), "").trim()
    }
}

package com.vidlab.app.data.scraper

data class ParsedMediaInfo(
    val cleanTitle: String,
    val seriesKey: String,
    val season: Int = 1,
    val episode: Int? = null,
    val year: String? = null,
    val isSeries: Boolean = false,
    val isAbsoluteEpisode: Boolean = false
)

object FilenameSanitizer {
    private val bracketsPattern = Regex("""\[[^\]]*\]|\([^\)]*(?:1080p|720p|x264|x265|bluray|hevc|web|dvd|hdhub)[^\)]*\)""", RegexOption.IGNORE_CASE)
    
    private val noisePattern = Regex(
        """(?i)\b(1080p|720p|480p|2160p|4k|uhd|hdr|web-?dl|webrip|bluray|bdrip|brrip|dvdrip|hdtv|x264|x265|hevc|avc|10bit|8bit|aac|ac3|eac3|dts(?:-hd)?|truehd|atmos|ddp?\s*5\.?1|dd\s*5\.?1|5\.1|7\.1|2\.0|multi|repack|proper|amzn|nf|hbo|cr|jbent|eztv|rarbg|psa|hindi|english|japanese|tamil|telugu|org|subs?|4khdhub|absenth?|hdhub4u|tv|com)\b"""
    )
    private val standardSeasonEpPattern = Regex(
        """(?i)\b(?:s(\d{1,2})[ ._-]*e(\d{1,3})|(\d{1,2})x(\d{1,3}))\b"""
    )
    private val standaloneSeasonPattern = Regex(
        """(?i)\b(?:season|s)\.?\s*0*(\d{1,2})\b"""
    )
    private val absoluteEpPattern = Regex(
        """(?i)(?:e|ep|episode|\b)[ ._-]*(\d{2,4})\b"""
    )
    private val yearPattern = Regex("""\b(19\d{2}|20\d{2})\b""")

    // Precompiled regex for detecting already-structured files
    private val structuredSeriesFileRegex = Regex("""^.+ - S\d{2}E\d{2}(?: - .+)?\.mkv$""", RegexOption.IGNORE_CASE)
    private val structuredSeasonFolderRegex = Regex("""^.+ Season \d{1,2}$""", RegexOption.IGNORE_CASE)
    private val structuredMovieFileRegex = Regex("""^.+\s\((19\d{2}|20\d{2})\)\.mkv$""", RegexOption.IGNORE_CASE)

    fun isSeriesAlreadyStructured(filePath: String): Boolean {
        if (!filePath.endsWith(".mkv", ignoreCase = true)) return false
        val parts = filePath.trim('/').split('/')
        if (parts.size < 3) return false
        val fileName = parts.last()
        val seasonFolder = parts[parts.size - 2]
        return structuredSeriesFileRegex.matches(fileName) && structuredSeasonFolderRegex.matches(seasonFolder)
    }

    fun isMovieAlreadyStructured(filePath: String): Boolean {
        if (!filePath.endsWith(".mkv", ignoreCase = true)) return false
        val parts = filePath.trim('/').split('/')
        if (parts.size < 2) return false
        val fileName = parts.last()
        val movieFolder = parts[parts.size - 2]
        val expectedFolderName = fileName.removeSuffix(".mkv")
        return structuredMovieFileRegex.matches(fileName) && movieFolder.equals(expectedFolderName, ignoreCase = true)
    }

    fun parse(rawInput: String): ParsedMediaInfo {
        var text = rawInput.substringBeforeLast('.')
        text = bracketsPattern.replace(text, " ")
        text = noisePattern.replace(text, " ")
        text = text.replace(Regex("""[._\-]"""), " ")

        var season = 1
        var episode: Int? = null
        var isSeries = false
        var isAbsolute = false
        var year: String? = null

        val yrMatch = yearPattern.find(text)
        if (yrMatch != null) {
            year = yrMatch.groupValues[1]
        }

        val epMatch = standardSeasonEpPattern.find(text)
        if (epMatch != null) {
            isSeries = true
            val sStr = epMatch.groupValues[1].ifEmpty { epMatch.groupValues[3] }
            val eStr = epMatch.groupValues[2].ifEmpty { epMatch.groupValues[4] }
            if (sStr.isNotBlank()) season = sStr.toIntOrNull() ?: 1
            if (eStr.isNotBlank()) {
                val epVal = eStr.toIntOrNull()
                if (epVal != null && epVal >= 100 && season > 1) {
                    isAbsolute = true
                }
                episode = epVal
            }
            text = text.substring(0, epMatch.range.first)
        } else {
            val sMatch = standaloneSeasonPattern.find(text)
            if (sMatch != null) {
                isSeries = true
                season = sMatch.groupValues[1].toIntOrNull() ?: 1
                text = text.substring(0, sMatch.range.first)
            }
        }

        text = noisePattern.replace(text, " ")
        text = text.replace(Regex("""\b(5\s*1|7\s*1|2\s*0)\b"""), " ")

        val rawClean = text.replace(Regex("""\s+"""), " ").trim()

        val titleWithoutYear = if (year != null && isSeries) {
            rawClean.replace(year, " ").replace(Regex("""\s+"""), " ").trim()
        } else if (year != null && !isSeries) {
            rawClean.substringBefore(year).trim().ifEmpty { rawClean }
        } else rawClean

        val cleanTitle = if (titleWithoutYear.isNotBlank()) titleWithoutYear else rawClean
        val seriesKey = cleanTitle.lowercase().replace(Regex("[^a-z0-9]"), "")

        return ParsedMediaInfo(
            cleanTitle = cleanTitle,
            seriesKey = seriesKey,
            season = season,
            episode = episode,
            year = year,
            isSeries = isSeries,
            isAbsoluteEpisode = isAbsolute
        )
    }
}

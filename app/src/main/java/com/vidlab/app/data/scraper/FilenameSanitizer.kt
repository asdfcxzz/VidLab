package com.vidlab.app.data.scraper

data class ParsedMediaInfo(
    val cleanTitle: String,
    val season: Int = 1,
    val episode: Int? = null,
    val year: String? = null,
    val isSeries: Boolean = false,
    val isAlreadyStandardized: Boolean = false
)

object FilenameSanitizer {
    private val standardizedSeriesPattern = Regex(
        """^(.+)\s-\sS(\d{2})E(\d{2,3})(?:\s-\s(.+))?\.mkv$""",
        RegexOption.IGNORE_CASE
    )
    private val standardizedMoviePattern = Regex(
        """^(.+?)(?:\s\((\d{4})\))?\.mkv$""",
        RegexOption.IGNORE_CASE
    )

    private val noisePattern = Regex(
        """(?i)\b(1080p|720p|480p|2160p|4k|uhd|hdr|web-?dl|webrip|bluray|bdrip|brrip|dvdrip|hdtv|x264|x265|hevc|avc|10bit|8bit|aac|ac3|dts|ddp5\.1|dd5\.1|multi|repack|proper|amzn|nf|hbo|cr|jbent|eztv|rarbg|psa|4khdhub\.com)\b"""
    )
    private val seasonEpPattern = Regex(
        """(?i)\b(?:s(\d{1,2})[ ._-]*e(\d{1,3})|(\d{1,2})x(\d{1,3})|\bep?\.?\s*(\d{1,3}))\b"""
    )
    private val standaloneSeasonPattern = Regex(
        """(?i)\b(?:season|s)\.?\s*0*(\d{1,2})\b"""
    )
    private val yearPattern = Regex("""\b(19\d{2}|20\d{2})\b""")

    fun isPathAlreadyStructured(path: String): Boolean {
        val fileName = path.substringAfterLast('/')
        val parentFolder = path.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
        
        // Matches "{Show} Season {X}/{Show} - SxxExx - Title.mkv"
        if (standardizedSeriesPattern.matches(fileName) && parentFolder.contains("Season", ignoreCase = true)) {
            return true
        }
        // Matches "{Movie (Year)}/{Movie (Year)}.mkv"
        if (fileName.endsWith(".mkv", ignoreCase = true) && fileName.substringBeforeLast('.') == parentFolder) {
            return true
        }
        return false
    }

    fun parse(rawInput: String): ParsedMediaInfo {
        val fileName = rawInput.substringAfterLast('/')
        
        if (standardizedSeriesPattern.matches(fileName)) {
            val match = standardizedSeriesPattern.find(fileName)!!
            return ParsedMediaInfo(
                cleanTitle = match.groupValues[1].trim(),
                season = match.groupValues[2].toIntOrNull() ?: 1,
                episode = match.groupValues[3].toIntOrNull(),
                isSeries = true,
                isAlreadyStandardized = true
            )
        }

        var text = fileName.substringBeforeLast('.')
        text = text.replace(Regex("""[._\[\]\(\)\-]"""), " ")

        var season = 1
        var episode: Int? = null
        var isSeries = false
        var year: String? = null

        val epMatch = seasonEpPattern.find(text)
        if (epMatch != null) {
            isSeries = true
            val sStr = epMatch.groupValues[1].ifEmpty { epMatch.groupValues[3] }
            val eStr = epMatch.groupValues[2].ifEmpty { epMatch.groupValues[4] }.ifEmpty { epMatch.groupValues[5] }
            if (sStr.isNotBlank()) season = sStr.toIntOrNull() ?: 1
            if (eStr.isNotBlank()) {
                val parsedEp = eStr.toIntOrNull() ?: 1
                // Normalize absolute continuous anime episodes (e.g. S07E139-S07E159 -> 1..21)
                episode = if (season > 1 && parsedEp > 100) {
                    (parsedEp % 100)
                } else parsedEp
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

        val yearMatch = yearPattern.find(text)
        if (yearMatch != null) {
            year = yearMatch.groupValues[1]
            if (!isSeries) {
                text = text.substringBefore(year)
            }
        }

        text = noisePattern.replace(text, " ")
        val clean = text.replace(Regex("""\s+"""), " ").trim()

        return ParsedMediaInfo(
            cleanTitle = if (clean.isNotBlank()) clean else rawInput.substringBeforeLast('.'),
            season = season,
            episode = episode,
            year = year,
            isSeries = isSeries,
            isAlreadyStandardized = false
        )
    }
}

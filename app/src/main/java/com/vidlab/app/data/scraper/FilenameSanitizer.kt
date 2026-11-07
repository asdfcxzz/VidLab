package com.vidlab.app.data.scraper

data class ParsedMediaInfo(
    val cleanTitle: String,
    val season: Int = 1,
    val episode: Int? = null,
    val year: String? = null,
    val isSeries: Boolean = false
)

object FilenameSanitizer {
    private val noisePattern = Regex(
        """(?i)\b(1080p|720p|480p|2160p|4k|uhd|hdr|web-?dl|webrip|bluray|bdrip|brrip|dvdrip|hdtv|x264|x265|hevc|avc|10bit|8bit|aac|ac3|dts|ddp5\.1|dd5\.1|multi|repack|proper|amzn|nf|hbo|jbent|eztv|rarbg|psa)\b"""
    )
    private val seasonEpPattern = Regex(
        """(?i)\b(?:s(\d{1,2})[ ._-]*e(\d{1,3})|(\d{1,2})x(\d{1,3})|\bep?\.?\s*(\d{1,3}))\b"""
    )
    private val standaloneSeasonPattern = Regex(
        """(?i)\b(?:season|s)\.?\s*(\d{1,2})\b"""
    )
    private val yearPattern = Regex("""\b(19\d{2}|20\d{2})\b""")

    fun parse(rawInput: String): ParsedMediaInfo {
        // Remove file extension if present
        var text = rawInput.substringBeforeLast('.')

        // Replace dots, underscores, brackets with spaces
        text = text.replace(Regex("""[._\[\]\(\)\-]"""), " ")

        var season = 1
        var episode: Int? = null
        var isSeries = false
        var year: String? = null

        // Check Year
        val yearMatch = yearPattern.find(text)
        if (yearMatch != null) {
            year = yearMatch.groupValues[1]
        }

        // Check S01E01 / 1x01
        val epMatch = seasonEpPattern.find(text)
        if (epMatch != null) {
            isSeries = true
            val sStr = epMatch.groupValues[1].ifEmpty { epMatch.groupValues[3] }
            val eStr = epMatch.groupValues[2].ifEmpty { epMatch.groupValues[4] }.ifEmpty { epMatch.groupValues[5] }
            if (sStr.isNotBlank()) season = sStr.toIntOrNull() ?: 1
            if (eStr.isNotBlank()) episode = eStr.toIntOrNull()

            // Cut off title before the episode token
            text = text.substring(0, epMatch.range.first)
        } else {
            // Check standalone Season (e.g. Game of Thrones S03)
            val sMatch = standaloneSeasonPattern.find(text)
            if (sMatch != null) {
                isSeries = true
                season = sMatch.groupValues[1].toIntOrNull() ?: 1
                text = text.substring(0, sMatch.range.first)
            }
        }

        // Strip noise words
        text = noisePattern.replace(text, " ")
        if (year != null && !isSeries) {
            text = text.substringBefore(year)
        }

        val clean = text.replace(Regex("""\s+"""), " ").trim()
        return ParsedMediaInfo(
            cleanTitle = if (clean.isNotBlank()) clean else rawInput.substringBeforeLast('.'),
            season = season,
            episode = episode,
            year = year,
            isSeries = isSeries
        )
    }
}

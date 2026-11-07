package com.vidlab.app.data.scraper

data class ParsedMediaInfo(
    val cleanTitle: String,
    val canonicalKey: String,
    val year: Int? = null,
    val season: Int = 1,
    val episode: Int? = null,
    val isSeries: Boolean = false
)

object FilenameSanitizer {
    private val episodeRegex = Regex("""(?i)(?:[ ._-]+)?(?:s(\d{1,2})[ ._-]*e(\d{1,3})|(\d{1,2})x(\d{1,3}))(?:[ ._-]+(.*?))?(?:\.mkv|\.mp4|\.webm|\.avi|$)""")
    private val seasonFolderRegex = Regex("""(?i)(?:[ ._-]+)?(?:season\s*(\d{1,2})|s(\d{1,2}))\b.*""")
    private val yearRegex = Regex("""\b(19\d{2}|20\d{2})\b""")
    
    private val sceneJunk = Regex(
        """(?i)\b(1080p|720p|2160p|4k|uhd|hdr|hdr10|bluray|bdrip|web-dl|webrip|web|hdtv|x264|x265|hevc|avc|aac|ac3|dts|ddp[0-9.]*|truehd|atmos|remux|repack|proper|multi|amzn|hindi|english|japanese|sub|dub|4khdhub|yts|lumix|10bit)\b|\[.*?\]|\(.*?\)|(?<=[a-z0-9])-(?=[a-z0-9])"""
    )

    fun parse(raw: String): ParsedMediaInfo {
        val normalized = raw.substringBeforeLast('.')
            .replace('.', ' ')
            .replace('_', ' ')

        val epMatch = episodeRegex.find(normalized)
        if (epMatch != null) {
            val titlePart = normalized.substring(0, epMatch.range.first)
            val sNum = epMatch.groupValues[1].ifEmpty { epMatch.groupValues[3] }.toIntOrNull() ?: 1
            val eNum = epMatch.groupValues[2].ifEmpty { epMatch.groupValues[4] }.toIntOrNull() ?: 1

            val clean = cleanText(titlePart)
            return ParsedMediaInfo(
                cleanTitle = clean,
                canonicalKey = generateCanonicalKey(clean),
                season = sNum,
                episode = eNum,
                isSeries = true
            )
        }

        var working = normalized
        val sFolderMatch = seasonFolderRegex.find(working)
        var detectedSeason = 1
        var isSeriesFolder = false

        if (sFolderMatch != null) {
            isSeriesFolder = true
            detectedSeason = sFolderMatch.groupValues[1].ifEmpty { sFolderMatch.groupValues[2] }.toIntOrNull() ?: 1
            working = working.substring(0, sFolderMatch.range.first)
        }

        val yMatch = yearRegex.find(working)
        val year = yMatch?.groupValues?.get(1)?.toIntOrNull()
        if (yMatch != null) {
            working = working.substring(0, yMatch.range.first)
        }

        val cleanFinal = cleanText(working)

        return ParsedMediaInfo(
            cleanTitle = cleanFinal,
            canonicalKey = generateCanonicalKey(cleanFinal),
            year = year,
            season = detectedSeason,
            isSeries = isSeriesFolder
        )
    }

    private fun cleanText(text: String): String {
        return text
            .replace(yearRegex, " ")
            .replace(sceneJunk, " ")
            .replace(Regex("""[.\-_+\[\]()]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun generateCanonicalKey(text: String): String {
        return text.lowercase()
            .replace(yearRegex, "")
            .replace(Regex("""\b(the|a|an)\b"""), "")
            .replace(Regex("[^a-z0-9]"), "")
    }
}

package com.vidlab.app.data.scraper

data class ParsedMediaInfo(
    val cleanTitle: String,
    val seriesKey: String,
    val season: Int = 1,
    val episode: Int? = null,
    val year: String? = null,
    val isSeries: Boolean = false,
    val isAbsoluteEpisode: Boolean = false,
    val existingEpisodeTitle: String? = null
)

object FilenameSanitizer {
    private val bracketsPattern = Regex("""\[[^\]]*\]|\([^\)]*\)""", RegexOption.IGNORE_CASE)
    
    private val noisePattern = Regex(
        """(?i)\b(1080p|720p|480p|2160p|4k|uhd|hdr|imax|web-?dl|webrip|bluray|bdrip|brrip|dvdrip|hdtv|x264|x265|hevc|avc|10bit|8bit|aac|ac3|eac3|dts(?:-hd)?|truehd|atmos|ddp?\s*5\.?1|dd\s*5\.?1|5\.1|7\.1|2\.0|multi|repack|proper|amzn|nf|hbo|cr|jbent|eztv|rarbg|psa|hin(?:di)?|tam(?:il)?|tel(?:ugu)?|kan(?:nada)?|eng(?:lish)?|jap(?:anese)?|org|line|esubs?|subs?|4khdhub|absenth?|absinth|hdhub4u|moviesdrives(?:\.bid)?|bid|tv|com|nano-lumix|lumix|yt|opus|tv\d{4}|movie\d{4})\b"""
    )
    private val standardSeasonEpPattern = Regex(
        """(?i)\b(?:s(\d{1,2})[ ._-]*e(\d{1,3})|(\d{1,2})x(\d{1,3}))\b"""
    )
    private val standaloneSeasonPattern = Regex(
        """(?i)\b(?:season|s)\.?\s*0*(\d{1,2})\b"""
    )
    private val yearPattern = Regex("""\b(19\d{2}|20\d{2})\b""")

    fun parse(rawInput: String, forceMovie: Boolean = false): ParsedMediaInfo {
        var text = rawInput.substringBeforeLast('.')
        text = text.replace(Regex("""(?i)(tv|movie)\d{4}"""), " ")

        var year: String? = null
        val yrMatch = yearPattern.find(text)
        if (yrMatch != null) {
            year = yrMatch.groupValues[1]
        }

        var existingTitle: String? = null
        val epMatch = standardSeasonEpPattern.find(text)
        if (epMatch != null && !forceMovie) {
            val afterEp = text.substring(epMatch.range.last + 1).trim()
            val cleanedAfter = noisePattern.replace(bracketsPattern.replace(afterEp, " "), " ")
                .replace(Regex("""^[ ._-]+"""), "")
                .trim()
            if (cleanedAfter.length > 2) {
                existingTitle = cleanedAfter
            }
        }

        // Strip bracket contents first
        text = bracketsPattern.replace(text, " ")
        text = noisePattern.replace(text, " ")
        text = text.replace(Regex("""[._\-]"""), " ")

        var season = 1
        var episode: Int? = null
        var isSeries = false
        var isAbsolute = false

        if (forceMovie) {
            text = text.replace(Regex("""(?i)\b(?:s\d{1,2}e\d{1,3}|\d{1,2}x\d{1,3}|s\d{1,2})\b"""), " ")
            if (year != null) text = text.replace(year, " ")
            text = noisePattern.replace(text, " ")
            val clean = text.replace(Regex("""\s+"""), " ").trim()
            return ParsedMediaInfo(
                cleanTitle = clean.ifEmpty { rawInput.substringBeforeLast('.') },
                seriesKey = clean.lowercase().replace(Regex("""[^a-z0-9]"""), ""),
                season = 1,
                episode = null,
                year = year,
                isSeries = false,
                isAbsoluteEpisode = false,
                existingEpisodeTitle = null
            )
        }

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

        val titleWithoutYear = if (year != null) {
            rawClean.replace(year, " ").replace(Regex("""\s+"""), " ").trim()
        } else rawClean

        val cleanTitle = if (titleWithoutYear.isNotBlank()) titleWithoutYear else rawClean
        val seriesKey = cleanTitle.lowercase().replace(Regex("""[^a-z0-9]"""), "")

        return ParsedMediaInfo(
            cleanTitle = cleanTitle,
            seriesKey = seriesKey,
            season = season,
            episode = episode,
            year = year,
            isSeries = isSeries,
            isAbsoluteEpisode = isAbsolute,
            existingEpisodeTitle = existingTitle
        )
    }
}

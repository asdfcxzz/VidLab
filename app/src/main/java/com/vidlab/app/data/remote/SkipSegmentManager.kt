package com.vidlab.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class SegmentType(val label: String) {
    INTRO("Skip Intro"),
    RECAP("Skip Recap"),
    OUTRO("Skip Outro"),
    CHAPTER("Chapter")
}

data class SkipSegment(
    val startMs: Long,
    val endMs: Long,
    val type: SegmentType,
    val title: String = type.label
)

object SkipSegmentManager {
    private const val USER_AGENT = "VidLab/2.4 (Android)"
    private const val TMDB_API_KEY = "4ef0d7355d9ffb5151e987764708ce96"

    private fun fetchJson(urlStr: String): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        }.getOrNull()
    }

    suspend fun fetchSegmentsForMedia(rawTitleOrFilename: String, durationMs: Long): List<SkipSegment> =
        withContext(Dispatchers.IO) {
            val cleanName = rawTitleOrFilename
                .substringAfterLast("/")
                .substringBeforeLast(".")
                .replace('.', ' ')
                .replace('_', ' ')
                .trim()

            val epRegex = Regex("""(?i)(.*?)\s*[-–]?\s*S(\d{1,2})E(\d{1,3})""")
            val epMatch = epRegex.find(cleanName)

            val results = mutableListOf<SkipSegment>()

            if (epMatch != null) {
                val showTitle = epMatch.groupValues[1].trim()
                val seasonNum = epMatch.groupValues[2].toIntOrNull() ?: 1
                val episodeNum = epMatch.groupValues[3].toIntOrNull() ?: 1

                // 1. Try TheIntroDB (covers Western TV shows + anime via TMDb ID)
                results += fetchFromIntroDbTv(showTitle, seasonNum, episodeNum)

                // 2. If empty, try AniSkip via Jikan MAL ID (covers anime OP/ED/Recap)
                if (results.isEmpty()) {
                    results += fetchFromAniSkip(showTitle, episodeNum, durationMs)
                }
            } else {
                // Movie: query TheIntroDB movie credits/intro timestamps via TMDb ID
                val yearMatch = Regex("""\((\d{4})\)""").find(cleanName)
                val year = yearMatch?.groupValues?.get(1)
                val movieTitle = cleanName.replace(Regex("""\(\d{4}\).*"""), "").trim()
                results += fetchFromIntroDbMovie(movieTitle, year)
            }

            results
                .filter { it.endMs > it.startMs + 2000L }
                .sortedBy { it.startMs }
        }

    private fun fetchFromIntroDbTv(title: String, season: Int, episode: Int): List<SkipSegment> {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val searchBody = fetchJson("https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encoded")
            ?: return emptyList()
        val first = JSONObject(searchBody).optJSONArray("results")?.optJSONObject(0) ?: return emptyList()
        val tmdbId = first.optInt("id", 0)
        if (tmdbId <= 0) return emptyList()

        val segBody = fetchJson("https://api.theintrodb.org/v1/tv/$tmdbId/season/$season/episode/$episode")
            ?: return emptyList()
        return parseIntroDbJson(segBody)
    }

    private fun fetchFromIntroDbMovie(title: String, year: String?): List<SkipSegment> {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val yearParam = if (!year.isNullOrBlank()) "&year=$year" else ""
        val searchBody = fetchJson("https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encoded$yearParam")
            ?: return emptyList()
        val first = JSONObject(searchBody).optJSONArray("results")?.optJSONObject(0) ?: return emptyList()
        val tmdbId = first.optInt("id", 0)
        if (tmdbId <= 0) return emptyList()

        val segBody = fetchJson("https://api.theintrodb.org/v1/movie/$tmdbId")
            ?: return emptyList()
        return parseIntroDbJson(segBody)
    }

    private fun parseIntroDbJson(body: String): List<SkipSegment> {
        val list = mutableListOf<SkipSegment>()
        runCatching {
            val root = JSONObject(body)
            val arr = root.optJSONArray("segments") ?: root.optJSONArray("timestamps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val startSec = obj.optDouble("start_sec", obj.optDouble("start", -1.0))
                val endSec = obj.optDouble("end_sec", obj.optDouble("end", -1.0))
                if (startSec >= 0.0 && endSec > startSec) {
                    val rawType = obj.optString("type", "intro").lowercase()
                    val type = when {
                        "recap" in rawType -> SegmentType.RECAP
                        "outro" in rawType || "credit" in rawType || "ed" == rawType -> SegmentType.OUTRO
                        else -> SegmentType.INTRO
                    }
                    list.add(SkipSegment((startSec * 1000).toLong(), (endSec * 1000).toLong(), type))
                }
            }
        }
        return list
    }

    private fun fetchFromAniSkip(title: String, episode: Int, durationMs: Long): List<SkipSegment> {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val jikanBody = fetchJson("https://api.jikan.moe/v4/anime?q=$encoded&limit=1") ?: return emptyList()
        val malId = JSONObject(jikanBody)
            .optJSONArray("data")
            ?.optJSONObject(0)
            ?.optInt("mal_id", 0) ?: 0
        if (malId <= 0) return emptyList()

        val epLenSec = if (durationMs > 0L) (durationMs / 1000.0) else 1440.0
        val skipUrl = "https://api.aniskip.com/v2/skip-times/$malId/$episode?types=op&types=ed&types=recap&types=mixed-op&episodeLength=$epLenSec"
        val skipBody = fetchJson(skipUrl) ?: return emptyList()

        val list = mutableListOf<SkipSegment>()
        runCatching {
            val root = JSONObject(skipBody)
            if (!root.optBoolean("found", false)) return@runCatching
            val results = root.optJSONArray("results") ?: return@runCatching
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val interval = item.optJSONObject("interval") ?: continue
                val startSec = interval.optDouble("startTime", -1.0)
                val endSec = interval.optDouble("endTime", -1.0)
                if (startSec >= 0.0 && endSec > startSec) {
                    val skipType = item.optString("skipType", "op").lowercase()
                    val type = when {
                        "recap" in skipType -> SegmentType.RECAP
                        "ed" in skipType -> SegmentType.OUTRO
                        else -> SegmentType.INTRO
                    }
                    list.add(SkipSegment((startSec * 1000).toLong(), (endSec * 1000).toLong(), type))
                }
            }
        }
        return list
    }

    fun classifyChapterTitle(title: String): SegmentType {
        val t = title.lowercase().trim()
        return when {
            t == "op" || t.startsWith("op ") || "opening" in t || "intro" in t -> SegmentType.INTRO
            "recap" in t -> SegmentType.RECAP
            t == "ed" || t.startsWith("ed ") || "ending" in t || "outro" in t || "credit" in t -> SegmentType.OUTRO
            else -> SegmentType.CHAPTER
        }
    }
}

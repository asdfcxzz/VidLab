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

    private fun postJson(urlStr: String, body: String): String? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        }.getOrNull()
    }

    suspend fun fetchSegmentsForMedia(
        rawTitleOrFilename: String,
        durationMs: Long,
        streamUrl: String = ""
    ): List<SkipSegment> = withContext(Dispatchers.IO) {
        val mkvChapters = if (streamUrl.startsWith("http") && streamUrl.lowercase().substringBefore("?").endsWith(".mkv")) {
            parseMkvChaptersFromStream(streamUrl, durationMs)
        } else emptyList()

        val cleanName = rawTitleOrFilename
            .substringAfterLast("/")
            .substringBeforeLast(".")
            .replace('.', ' ')
            .replace('_', ' ')
            .trim()

        val epRegex = Regex("""(?i)(.*?)\s*[-–]?\s*S(\d{1,2})E(\d{1,3})""")
        val epMatch = epRegex.find(cleanName)

        val onlineSegments = if (epMatch != null) {
            val showTitle = epMatch.groupValues[1].trim()
            val seasonNum = epMatch.groupValues[2].toIntOrNull() ?: 1
            val episodeNum = epMatch.groupValues[3].toIntOrNull() ?: 1

            // Query AniSkip (via AniList MAL ID + Jikan fallback) AND TheIntroDB
            val aniSegments = fetchFromAniSkip(showTitle, seasonNum, episodeNum, durationMs)
            if (aniSegments.isNotEmpty()) aniSegments
            else fetchFromIntroDbTv(showTitle, seasonNum, episodeNum)
        } else {
            val yearMatch = Regex("""\((\d{4})\)""").find(cleanName)
            val year = yearMatch?.groupValues?.get(1)
            val movieTitle = cleanName.replace(Regex("""\(\d{4}\).*"""), "").trim()
            fetchFromIntroDbMovie(movieTitle, year)
        }

        // Merge online skippable segments (precise OP/ED) with MKV chapter dividers
        val combined = if (onlineSegments.isNotEmpty()) {
            val chapterDividers = mkvChapters.filter { it.type == SegmentType.CHAPTER }
            onlineSegments + chapterDividers
        } else {
            mkvChapters
        }

        combined
            .filter { it.endMs > it.startMs + 1500L }
            .distinctBy { "${it.type}_${it.startMs / 2000L}" }
            .sortedBy { it.startMs }
    }

    private fun parseMkvChaptersFromStream(streamUrl: String, durationMs: Long): List<SkipSegment> {
        return runCatching {
            val headBytes = fetchRangeBytes(streamUrl, "bytes=0-786431") ?: ByteArray(0)
            val tailBytes = fetchRangeBytes(streamUrl, "bytes=-131072") ?: ByteArray(0)
            val combined = headBytes + tailBytes
            if (combined.isEmpty()) return@runCatching emptyList()

            data class RawChapter(val startMs: Long, val endMs: Long, val title: String)
            val rawChapters = mutableListOf<RawChapter>()

            var i = 0
            while (i < combined.size - 12) {
                if ((combined[i].toInt() and 0xFF) == 0xB6) {
                    val (atomLen, lenBytes) = readEbmlVintSize(combined, i + 1)
                    if (atomLen in 10..4096 && i + 1 + lenBytes + atomLen <= combined.size) {
                        val atomStart = i + 1 + lenBytes
                        val atomEnd = atomStart + atomLen.toInt()
                        var startNs = -1L
                        var endNs = -1L
                        var chapTitle = ""

                        var j = atomStart
                        while (j < atomEnd - 2) {
                            val b0 = combined[j].toInt() and 0xFF
                            if (b0 == 0x80) {
                                val (_, dLenBytes) = readEbmlVintSize(combined, j + 1)
                                j += 1 + dLenBytes
                                continue
                            }
                            if (b0 == 0x91) {
                                val (vLen, vLenBytes) = readEbmlVintSize(combined, j + 1)
                                val valPos = j + 1 + vLenBytes
                                if (vLen in 1..8 && valPos + vLen <= atomEnd) {
                                    startNs = readBigEndianLong(combined, valPos, vLen.toInt())
                                }
                                j = valPos + vLen.toInt().coerceAtLeast(1)
                                continue
                            }
                            if (b0 == 0x92) {
                                val (vLen, vLenBytes) = readEbmlVintSize(combined, j + 1)
                                val valPos = j + 1 + vLenBytes
                                if (vLen in 1..8 && valPos + vLen <= atomEnd) {
                                    endNs = readBigEndianLong(combined, valPos, vLen.toInt())
                                }
                                j = valPos + vLen.toInt().coerceAtLeast(1)
                                continue
                            }
                            if (b0 == 0x85) {
                                val (vLen, vLenBytes) = readEbmlVintSize(combined, j + 1)
                                val valPos = j + 1 + vLenBytes
                                if (vLen in 1..256 && valPos + vLen <= atomEnd) {
                                    chapTitle = String(combined, valPos, vLen.toInt(), Charsets.UTF_8).trim()
                                }
                                j = valPos + vLen.toInt().coerceAtLeast(1)
                                continue
                            }
                            j++
                        }

                        if (startNs >= 0L) {
                            val sMs = startNs / 1_000_000L
                            val eMs = if (endNs > startNs) endNs / 1_000_000L else -1L
                            rawChapters.add(RawChapter(sMs, eMs, chapTitle.ifBlank { "Chapter" }))
                        }
                        i = atomEnd
                        continue
                    }
                }
                i++
            }

            if (rawChapters.isEmpty()) return@runCatching emptyList()

            val sorted = rawChapters.sortedBy { it.startMs }
            val effectiveDur = if (durationMs > 0L) durationMs else (sorted.maxOf { it.startMs } + 90_000L)
            val initial = sorted.mapIndexed { idx, ch ->
                val segType = classifyChapterTitle(ch.title)
                val nextStart = if (idx + 1 < sorted.size) sorted[idx + 1].startMs else effectiveDur
                val inferredEnd = when {
                    ch.endMs > ch.startMs -> ch.endMs
                    // If named OP/ED has no explicit ChapterTimeEnd and next chapter is >3m away, cap at 90s
                    segType != SegmentType.CHAPTER && (nextStart - ch.startMs) > 180_000L ->
                        (ch.startMs + 90_000L).coerceAtMost(nextStart)
                    else -> nextStart
                }
                SkipSegment(ch.startMs, inferredEnd, segType, ch.title)
            }

            val hasNamedSkip = initial.any { it.type != SegmentType.CHAPTER }
            if (!hasNamedSkip && effectiveDur > 240_000L) {
                initial.mapIndexed { idx, seg ->
                    val segLen = seg.endMs - seg.startMs
                    val isOpEdLen = segLen in 45_000L..150_000L
                    when {
                        isOpEdLen && seg.startMs < 420_000L && idx <= 3 ->
                            seg.copy(type = SegmentType.INTRO, title = "Intro")
                        isOpEdLen && (effectiveDur - seg.endMs) < 270_000L && idx >= initial.size - 3 ->
                            seg.copy(type = SegmentType.OUTRO, title = "Outro")
                        else -> seg
                    }
                }
            } else {
                initial
            }
        }.getOrDefault(emptyList())
    }

    private fun fetchRangeBytes(streamUrl: String, rangeHeader: String): ByteArray? {
        return runCatching {
            val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", rangeHeader)
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            if (conn.responseCode in listOf(200, 206)) {
                conn.inputStream.use { it.readBytes() }
            } else null
        }.getOrNull()
    }

    private fun readEbmlVintSize(buf: ByteArray, offset: Int): Pair<Long, Int> {
        if (offset >= buf.size) return -1L to 0
        val first = buf[offset].toInt() and 0xFF
        if (first == 0) return -1L to 0
        var mask = 0x80
        var width = 1
        while (width <= 8 && (first and mask) == 0) {
            mask = mask shr 1
            width++
        }
        if (width > 8 || offset + width > buf.size) return -1L to 0
        var value = (first and (mask - 1)).toLong()
        for (k in 1 until width) {
            value = (value shl 8) or (buf[offset + k].toInt() and 0xFF).toLong()
        }
        return value to width
    }

    private fun readBigEndianLong(buf: ByteArray, offset: Int, len: Int): Long {
        var v = 0L
        for (k in 0 until len) {
            v = (v shl 8) or (buf[offset + k].toInt() and 0xFF).toLong()
        }
        return v
    }

    private fun fetchMalIdsViaAniList(title: String): List<Int> {
        val query = """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 5) {
                media(search: ${'$'}search, type: ANIME, sort: POPULARITY_DESC) {
                  idMal
                }
              }
            }
        """.trimIndent()
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject().put("search", title))
        }.toString()
        val body = postJson("https://graphql.anilist.co", payload) ?: return emptyList()
        val list = mutableListOf<Int>()
        runCatching {
            val mediaArr = JSONObject(body)
                .optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media") ?: return@runCatching
            for (i in 0 until mediaArr.length()) {
                val idMal = mediaArr.optJSONObject(i)?.optInt("idMal", 0) ?: 0
                if (idMal > 0) list.add(idMal)
            }
        }
        return list
    }

    private fun fetchFromAniSkip(title: String, season: Int, episode: Int, durationMs: Long): List<SkipSegment> {
        val searchTitle = if (season > 1) "$title Season $season" else title
        val malIds = LinkedHashSet<Int>()
        malIds.addAll(fetchMalIdsViaAniList(searchTitle))
        if (season > 1) malIds.addAll(fetchMalIdsViaAniList(title))

        if (malIds.isEmpty()) {
            val encoded = URLEncoder.encode(searchTitle, "UTF-8")
            val jikanBody = fetchJson("https://api.jikan.moe/v4/anime?q=$encoded&limit=3")
            if (jikanBody != null) {
                val dataArr = JSONObject(jikanBody).optJSONArray("data")
                if (dataArr != null) {
                    for (idx in 0 until dataArr.length()) {
                        val malId = dataArr.optJSONObject(idx)?.optInt("mal_id", 0) ?: 0
                        if (malId > 0) malIds.add(malId)
                    }
                }
            }
        }

        val epLenSec = if (durationMs > 0L) (durationMs / 1000.0) else 1440.0
        for (malId in malIds) {
            val urlA = "https://api.aniskip.com/v2/skip-times/$malId/$episode?types=op&types=ed&types=recap&types=mixed-op&episodeLength=$epLenSec"
            val urlB = "https://api.aniskip.com/v2/skip-times/$malId/$episode?types[]=op&types[]=ed&types[]=recap&types[]=mixed-op&episodeLength=0"
            val skipBody = fetchJson(urlA) ?: fetchJson(urlB) ?: continue
            val parsed = parseAniSkipJson(skipBody)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun parseAniSkipJson(skipBody: String): List<SkipSegment> {
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

    private fun fetchFromIntroDbTv(title: String, season: Int, episode: Int): List<SkipSegment> {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val searchBody = fetchJson("https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encoded")
            ?: return emptyList()
        val first = JSONObject(searchBody).optJSONArray("results")?.optJSONObject(0) ?: return emptyList()
        val tmdbId = first.optInt("id", 0)
        if (tmdbId <= 0) return emptyList()

        val segBody = fetchJson("https://api.theintrodb.org/v1/tv/$tmdbId/season/$season/episode/$episode")
            ?: fetchJson("https://introdb.app/api/tv/$tmdbId/$season/$episode")
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
            ?: fetchJson("https://introdb.app/api/movie/$tmdbId")
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

    fun classifyChapterTitle(title: String): SegmentType {
        val t = title.lowercase().trim()
        return when {
            t == "op" || t.startsWith("op ") || t.endsWith(" op") || "opening" in t || "intro" in t || "theme" in t -> SegmentType.INTRO
            "recap" in t || "previously" in t -> SegmentType.RECAP
            t == "ed" || t.startsWith("ed ") || t.endsWith(" ed") || "ending" in t || "outro" in t || "credit" in t || "preview" in t || "next time" in t -> SegmentType.OUTRO
            else -> SegmentType.CHAPTER
        }
    }
}

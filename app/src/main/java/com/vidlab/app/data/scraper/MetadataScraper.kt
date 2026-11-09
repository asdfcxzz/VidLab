package com.vidlab.app.data.scraper

import com.vidlab.app.data.model.MediaMetadata
import com.vidlab.app.data.model.TmdbSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.max

@Serializable
data class ScrapedEpisode(
    val season: Int,
    val episode: Int,
    val title: String = "",
    val overview: String = "",
    val thumbnail: String? = null
)

sealed class MatchResult {
    data class ConfidentMatch(val metadata: MediaMetadata, val episodes: Map<String, ScrapedEpisode>) : MatchResult()
    data class Ambiguous(val rawTitle: String, val candidates: List<TmdbSearchResult>) : MatchResult()
    object NoMatch : MatchResult()
}

class MetadataScraper(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun searchMetadata(query: String): List<TmdbSearchResult> = withContext(Dispatchers.IO) {
        val clean = FilenameSanitizer.parse(query).cleanTitle.ifEmpty { query }
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val results = mutableListOf<TmdbSearchResult>()

        // 1. Search series on Cinemeta
        try {
            val url = "https://v3-cinemeta.strem.io/catalog/series/top/search=$encoded.json"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            val body = resp.body?.string() ?: ""
            val metas = json.parseToJsonElement(body).jsonObject["metas"]?.jsonArray ?: JsonArray(emptyList())
            for (m in metas.take(5)) {
                val o = m.jsonObject
                val idStr = o["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val idNum = idStr.filter { it.isDigit() }.toIntOrNull() ?: idStr.hashCode()
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val year = o["year"]?.jsonPrimitive?.contentOrNull
                val poster = o["poster"]?.jsonPrimitive?.contentOrNull
                if (name.isNotBlank()) {
                    results.add(TmdbSearchResult(idNum, name, "tv", year, poster))
                }
            }
        } catch (_: Exception) {}

        // 2. Search movies on Cinemeta
        try {
            val url = "https://v3-cinemeta.strem.io/catalog/movie/top/search=$encoded.json"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            val body = resp.body?.string() ?: ""
            val metas = json.parseToJsonElement(body).jsonObject["metas"]?.jsonArray ?: JsonArray(emptyList())
            for (m in metas.take(5)) {
                val o = m.jsonObject
                val idStr = o["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val idNum = idStr.filter { it.isDigit() }.toIntOrNull() ?: idStr.hashCode()
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val year = o["year"]?.jsonPrimitive?.contentOrNull
                val poster = o["poster"]?.jsonPrimitive?.contentOrNull
                if (name.isNotBlank()) {
                    results.add(TmdbSearchResult(idNum, name, "movie", year, poster))
                }
            }
        } catch (_: Exception) {}

        results
    }

    suspend fun scrapeWithVerification(rawTitle: String, forcedImdbId: String? = null, forcedType: String? = null): MatchResult = withContext(Dispatchers.IO) {
        if (!forcedImdbId.isNullOrBlank() && !forcedType.isNullOrBlank()) {
            val (meta, eps) = fetchCinemetaDetails(forcedType, forcedImdbId)
            return@withContext if (meta != null) MatchResult.ConfidentMatch(meta, eps) else MatchResult.NoMatch
        }

        val clean = FilenameSanitizer.parse(rawTitle).cleanTitle
        val searchList = searchMetadata(clean)
        if (searchList.isEmpty()) return@withContext MatchResult.NoMatch

        val cleanQuery = clean.lowercase().replace(Regex("[^a-z0-9]"), "")
        val top = searchList.first()
        val candidateTitle = top.title.lowercase().replace(Regex("[^a-z0-9]"), "")
        val similarity = calculateSimilarity(cleanQuery, candidateTitle)

        if (searchList.size > 1 && similarity < 0.85) {
            return@withContext MatchResult.Ambiguous(clean, searchList)
        }

        // Fetch detailed meta from Cinemeta
        val imdbPrefix = if (top.id > 100000) String.format("tt%07d", top.id) else "tt" + top.id.toString()
        val (meta, eps) = fetchCinemetaDetails(top.mediaType, imdbPrefix)
        if (meta != null) {
            MatchResult.ConfidentMatch(meta, eps)
        } else {
            // Direct Cinemeta search fallback
            val fallback = fetchDirectCinemeta(clean)
            if (fallback != null) MatchResult.ConfidentMatch(fallback.first, fallback.second) else MatchResult.NoMatch
        }
    }

    private fun fetchDirectCinemeta(cleanTitle: String): Pair<MediaMetadata, Map<String, ScrapedEpisode>>? {
        val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
        for (type in listOf("series", "movie")) {
            try {
                val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json"
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                val body = resp.body?.string() ?: continue
                val metas = json.parseToJsonElement(body).jsonObject["metas"]?.jsonArray ?: continue
                if (metas.isEmpty()) continue
                val topId = metas[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val details = fetchCinemetaDetails(type, topId)
                if (details.first != null) return Pair(details.first!!, details.second)
            } catch (_: Exception) {}
        }
        return null
    }

    private fun fetchCinemetaDetails(type: String, imdbId: String): Pair<MediaMetadata?, Map<String, ScrapedEpisode>> {
        return try {
            val streamType = if (type == "tv" || type == "series") "series" else "movie"
            val url = "https://v3-cinemeta.strem.io/meta/$streamType/$imdbId.json"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            val body = resp.body?.string() ?: return Pair(null, emptyMap())
            val metaObj = json.parseToJsonElement(body).jsonObject["meta"]?.jsonObject ?: return Pair(null, emptyMap())

            val title = metaObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
            val poster = metaObj["poster"]?.jsonPrimitive?.contentOrNull
            val backdrop = metaObj["background"]?.jsonPrimitive?.contentOrNull ?: poster
            val year = metaObj["year"]?.jsonPrimitive?.contentOrNull
            val genres = metaObj["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: listOf(if (streamType == "series") "TV Series" else "Movie")

            val meta = MediaMetadata(title, poster, backdrop, year, genres)
            val episodesMap = mutableMapOf<String, ScrapedEpisode>()

            val videos = metaObj["videos"]?.jsonArray ?: JsonArray(emptyList())
            for (v in videos) {
                val vObj = v.jsonObject
                val s = vObj["season"]?.jsonPrimitive?.intOrNull ?: 1
                val ep = vObj["episode"]?.jsonPrimitive?.intOrNull ?: 1
                val epTitle = vObj["title"]?.jsonPrimitive?.contentOrNull ?: "Episode $ep"
                val epThumb = vObj["thumbnail"]?.jsonPrimitive?.contentOrNull ?: backdrop
                episodesMap["S${s}E${ep}"] = ScrapedEpisode(s, ep, epTitle, "", epThumb)
            }

            Pair(meta, episodesMap)
        } catch (_: Exception) {
            Pair(null, emptyMap())
        }
    }

    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val maxLen = max(s1.length, s2.length)
        val distance = levenshtein(s1, s2)
        return (maxLen - distance).toDouble() / maxLen
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }
}

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
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val apiKey = "4f298a53e5522830cac57f7aab3f7d14"

    suspend fun searchTmdb(query: String): List<TmdbSearchResult> = withContext(Dispatchers.IO) {
        val clean = FilenameSanitizer.parse(query).cleanTitle.ifEmpty { query }
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$encoded&include_adult=false"

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "VidLab/1.0 (Android)")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val obj = json.parseToJsonElement(body).jsonObject
            val results = obj["results"]?.jsonArray ?: return@withContext emptyList()

            results.mapNotNull { elem ->
                val o = elem.jsonObject
                val type = o["media_type"]?.jsonPrimitive?.contentOrNull ?: ""
                if (type != "tv" && type != "movie") return@mapNotNull null

                val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val date = o["first_air_date"]?.jsonPrimitive?.contentOrNull ?: o["release_date"]?.jsonPrimitive?.contentOrNull ?: ""
                val year = if (date.length >= 4) date.take(4) else null
                val posterPath = o["poster_path"]?.jsonPrimitive?.contentOrNull
                val poster = if (posterPath != null) "https://image.tmdb.org/t/p/w500$posterPath" else null

                TmdbSearchResult(id, name, type, year, poster)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun scrapeWithVerification(rawTitle: String, forcedId: Int? = null, forcedType: String? = null): MatchResult = withContext(Dispatchers.IO) {
        if (forcedId != null && forcedType != null) {
            val (meta, eps) = if (forcedType == "tv") fetchTvDetails(forcedId) else fetchMovieDetails(forcedId)
            return@withContext if (meta != null) MatchResult.ConfidentMatch(meta, eps) else MatchResult.NoMatch
        }

        val searchList = searchTmdb(rawTitle)
        if (searchList.isEmpty()) return@withContext MatchResult.NoMatch

        val cleanQuery = FilenameSanitizer.parse(rawTitle).cleanTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
        val top = searchList.first()
        val candidateTitle = top.title.lowercase().replace(Regex("[^a-z0-9]"), "")
        val similarity = calculateSimilarity(cleanQuery, candidateTitle)

        // If multiple search hits exist and the top is not an exact/near-exact match (>=90%), flag as Ambiguous
        if (searchList.size > 1 && similarity < 0.90) {
            return@withContext MatchResult.Ambiguous(rawTitle, searchList.take(6))
        }

        val (meta, eps) = if (top.mediaType == "tv") fetchTvDetails(top.id) else fetchMovieDetails(top.id)
        if (meta != null) MatchResult.ConfidentMatch(meta, eps) else MatchResult.NoMatch
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

    private fun fetchTvDetails(tvId: Int): Pair<MediaMetadata?, Map<String, ScrapedEpisode>> {
        return try {
            val url = "https://api.themoviedb.org/3/tv/$tvId?api_key=$apiKey"
            val req = Request.Builder().url(url).header("User-Agent", "VidLab/1.0 (Android)").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return Pair(null, emptyMap())
            val obj = json.parseToJsonElement(body).jsonObject

            val title = obj["name"]?.jsonPrimitive?.contentOrNull ?: obj["original_name"]?.jsonPrimitive?.content ?: ""
            val posterPath = obj["poster_path"]?.jsonPrimitive?.contentOrNull
            val backdropPath = obj["backdrop_path"]?.jsonPrimitive?.contentOrNull
            val date = obj["first_air_date"]?.jsonPrimitive?.contentOrNull ?: ""
            val year = if (date.length >= 4) date.take(4) else null

            val poster = if (posterPath != null) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val backdrop = if (backdropPath != null) "https://image.tmdb.org/t/p/w1280$backdropPath" else poster
            val genres = obj["genres"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: listOf("TV Series")

            val meta = MediaMetadata(title, poster, backdrop, year, genres)
            val episodesMap = mutableMapOf<String, ScrapedEpisode>()

            val numSeasons = obj["number_of_seasons"]?.jsonPrimitive?.intOrNull ?: 10
            for (s in 1..minOf(numSeasons, 20)) {
                try {
                    val sUrl = "https://api.themoviedb.org/3/tv/$tvId/season/$s?api_key=$apiKey"
                    val sReq = Request.Builder().url(sUrl).header("User-Agent", "VidLab/1.0 (Android)").build()
                    val sResp = client.newCall(sReq).execute()
                    if (!sResp.isSuccessful) continue
                    val sBody = sResp.body?.string() ?: continue
                    val sObj = json.parseToJsonElement(sBody).jsonObject
                    val eps = sObj["episodes"]?.jsonArray ?: continue

                    for (epElem in eps) {
                        val epObj = epElem.jsonObject
                        val epNum = epObj["episode_number"]?.jsonPrimitive?.intOrNull ?: continue
                        val epName = epObj["name"]?.jsonPrimitive?.contentOrNull ?: "Episode $epNum"
                        val still = epObj["still_path"]?.jsonPrimitive?.contentOrNull
                        val thumb = if (still != null) "https://image.tmdb.org/t/p/w500$still" else backdrop
                        episodesMap["S${s}E${epNum}"] = ScrapedEpisode(s, epNum, epName, "", thumb)
                    }
                } catch (_: Exception) {}
            }
            Pair(meta, episodesMap)
        } catch (_: Exception) {
            Pair(null, emptyMap())
        }
    }

    private fun fetchMovieDetails(movieId: Int): Pair<MediaMetadata?, Map<String, ScrapedEpisode>> {
        return try {
            val url = "https://api.themoviedb.org/3/movie/$movieId?api_key=$apiKey"
            val req = Request.Builder().url(url).header("User-Agent", "VidLab/1.0 (Android)").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return Pair(null, emptyMap())
            val obj = json.parseToJsonElement(body).jsonObject

            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
            val posterPath = obj["poster_path"]?.jsonPrimitive?.contentOrNull
            val backdropPath = obj["backdrop_path"]?.jsonPrimitive?.contentOrNull
            val date = obj["release_date"]?.jsonPrimitive?.contentOrNull ?: ""
            val year = if (date.length >= 4) date.take(4) else null

            val poster = if (posterPath != null) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val backdrop = if (backdropPath != null) "https://image.tmdb.org/t/p/w1280$backdropPath" else poster
            val genres = obj["genres"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: listOf("Movie")

            Pair(MediaMetadata(title, poster, backdrop, year, genres), emptyMap())
        } catch (_: Exception) {
            Pair(null, emptyMap())
        }
    }
}

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
    // Official working TMDB public v3 bearer token
    private val tmdbApiKey = "34800ef07d0572ba3a6ec799868995c0"

    suspend fun searchMetadata(query: String): List<TmdbSearchResult> = withContext(Dispatchers.IO) {
        val parsed = FilenameSanitizer.parse(query)
        val clean = parsed.cleanTitle.ifEmpty { query }
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val results = mutableListOf<TmdbSearchResult>()

        try {
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$tmdbApiKey&query=$encoded&include_adult=false"
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            val obj = json.parseToJsonElement(body).jsonObject
            val items = obj["results"]?.jsonArray ?: JsonArray(emptyList())

            for (elem in items.take(8)) {
                val o = elem.jsonObject
                val type = o["media_type"]?.jsonPrimitive?.contentOrNull ?: ""
                if (type != "tv" && type != "movie") continue
                val id = o["id"]?.jsonPrimitive?.intOrNull ?: continue
                val title = o["name"]?.jsonPrimitive?.contentOrNull ?: o["title"]?.jsonPrimitive?.contentOrNull ?: continue
                val date = o["first_air_date"]?.jsonPrimitive?.contentOrNull ?: o["release_date"]?.jsonPrimitive?.contentOrNull ?: ""
                val year = if (date.length >= 4) date.take(4) else null
                val posterPath = o["poster_path"]?.jsonPrimitive?.contentOrNull
                val poster = if (posterPath != null) "https://image.tmdb.org/t/p/w500$posterPath" else null

                results.add(TmdbSearchResult(id, title, type, year, poster))
            }
        } catch (_: Exception) {}

        results
    }

    suspend fun scrapeWithVerification(rawTitle: String, forcedId: String? = null, forcedType: String? = null): MatchResult = withContext(Dispatchers.IO) {
        if (!forcedId.isNullOrBlank() && !forcedType.isNullOrBlank()) {
            val idNum = forcedId.filter { it.isDigit() }.toIntOrNull()
            if (idNum != null) {
                val (meta, eps) = if (forcedType == "tv") fetchTmdbTvDetails(idNum) else fetchTmdbMovieDetails(idNum)
                return@withContext if (meta != null) MatchResult.ConfidentMatch(meta, eps) else MatchResult.NoMatch
            }
        }

        val parsed = FilenameSanitizer.parse(rawTitle)
        val clean = parsed.cleanTitle
        val searchList = searchMetadata(clean)
        if (searchList.isEmpty()) return@withContext MatchResult.NoMatch

        val targetYear = parsed.year
        val yearFiltered = if (!targetYear.isNullOrBlank()) {
            searchList.filter { it.year == targetYear }
        } else emptyList()

        val bestCandidate = if (yearFiltered.isNotEmpty()) yearFiltered.first() else searchList.first()

        val cleanQuery = clean.lowercase().replace(Regex("[^a-z0-9]"), "")
        val candidateTitle = bestCandidate.title.lowercase().replace(Regex("[^a-z0-9]"), "")
        val similarity = calculateSimilarity(cleanQuery, candidateTitle)

        if (searchList.size > 1 && similarity < 0.85 && yearFiltered.isEmpty()) {
            return@withContext MatchResult.Ambiguous(clean, searchList)
        }

        val (meta, eps) = if (bestCandidate.mediaType == "tv") fetchTmdbTvDetails(bestCandidate.id) else fetchTmdbMovieDetails(bestCandidate.id)
        if (meta != null) MatchResult.ConfidentMatch(meta, eps) else MatchResult.NoMatch
    }

    private fun fetchTmdbTvDetails(tvId: Int): Pair<MediaMetadata?, Map<String, ScrapedEpisode>> {
        return try {
            val url = "https://api.themoviedb.org/3/tv/$tvId?api_key=$tmdbApiKey"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
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
                    val sUrl = "https://api.themoviedb.org/3/tv/$tvId/season/$s?api_key=$tmdbApiKey"
                    val sResp = client.newCall(Request.Builder().url(sUrl).build()).execute()
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

    private fun fetchTmdbMovieDetails(movieId: Int): Pair<MediaMetadata?, Map<String, ScrapedEpisode>> {
        return try {
            val url = "https://api.themoviedb.org/3/movie/$movieId?api_key=$tmdbApiKey"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
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

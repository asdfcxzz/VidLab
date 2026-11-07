package com.vidlab.app.data.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable
data class ScrapedMetadata(
    val title: String,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseYear: String? = null,
    val genres: List<String> = emptyList()
)

@Serializable
data class ScrapedEpisode(
    val season: Int,
    val episode: Int,
    val title: String = "",
    val overview: String = "",
    val thumbnail: String? = null
)

class MetadataScraper(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val tmdbApiKey = "4f298a53e5522830cac57f7aab3f7d14"

    suspend fun fetchDetailedMetadata(rawTitle: String): Pair<ScrapedMetadata?, Map<String, ScrapedEpisode>> = withContext(Dispatchers.IO) {
        val cleanName = FilenameSanitizer.parse(rawTitle).cleanTitle
        val encoded = URLEncoder.encode(cleanName, "UTF-8")

        val tvResult = fetchTmdbTv(encoded)
        if (tvResult != null) return@withContext tvResult

        val movieResult = fetchTmdbMovie(encoded)
        if (movieResult != null) return@withContext movieResult

        val tvMazeResult = fetchTvMaze(encoded)
        if (tvMazeResult != null) return@withContext tvMazeResult

        Pair(null, emptyMap())
    }

    private fun fetchTmdbTv(encodedQuery: String): Pair<ScrapedMetadata?, Map<String, ScrapedEpisode>>? {
        return try {
            val url = "https://api.themoviedb.org/3/search/tv?api_key=$tmdbApiKey&query=$encodedQuery"
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            val obj = json.parseToJsonElement(body).jsonObject
            val results = obj["results"]?.jsonArray ?: return null
            if (results.isEmpty()) return null

            val first = results[0].jsonObject
            val showId = first["id"]?.jsonPrimitive?.intOrNull ?: return null
            val title = first["name"]?.jsonPrimitive?.contentOrNull ?: first["original_name"]?.jsonPrimitive?.content ?: ""
            val overview = first["overview"]?.jsonPrimitive?.contentOrNull ?: ""
            val posterPath = first["poster_path"]?.jsonPrimitive?.contentOrNull
            val backdropPath = first["backdrop_path"]?.jsonPrimitive?.contentOrNull
            val firstAir = first["first_air_date"]?.jsonPrimitive?.contentOrNull ?: ""
            val year = if (firstAir.length >= 4) firstAir.take(4) else null

            val poster = if (posterPath != null) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val backdrop = if (backdropPath != null) "https://image.tmdb.org/t/p/w1280$backdropPath" else poster

            val meta = ScrapedMetadata(
                title = title,
                overview = overview,
                posterUrl = poster,
                backdropUrl = backdrop,
                releaseYear = year,
                genres = listOf("TV Series")
            )

            val episodesMap = mutableMapOf<String, ScrapedEpisode>()
            for (s in 1..8) {
                try {
                    val sUrl = "https://api.themoviedb.org/3/tv/$showId/season/$s?api_key=$tmdbApiKey"
                    val sResp = client.newCall(Request.Builder().url(sUrl).build()).execute()
                    if (!sResp.isSuccessful) continue
                    val sBody = sResp.body?.string() ?: continue
                    val sObj = json.parseToJsonElement(sBody).jsonObject
                    val eps = sObj["episodes"]?.jsonArray ?: continue
                    for (epElem in eps) {
                        val epObj = epElem.jsonObject
                        val epNum = epObj["episode_number"]?.jsonPrimitive?.intOrNull ?: continue
                        val epName = epObj["name"]?.jsonPrimitive?.contentOrNull ?: "Episode $epNum"
                        val stillPath = epObj["still_path"]?.jsonPrimitive?.contentOrNull
                        val thumb = if (stillPath != null) "https://image.tmdb.org/t/p/w500$stillPath" else backdrop
                        episodesMap["S${s}E${epNum}"] = ScrapedEpisode(
                            season = s,
                            episode = epNum,
                            title = epName,
                            overview = epObj["overview"]?.jsonPrimitive?.contentOrNull ?: "",
                            thumbnail = thumb
                        )
                    }
                } catch (_: Exception) {}
            }

            Pair(meta, episodesMap)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchTmdbMovie(encodedQuery: String): Pair<ScrapedMetadata?, Map<String, ScrapedEpisode>>? {
        return try {
            val url = "https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=$encodedQuery"
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            val obj = json.parseToJsonElement(body).jsonObject
            val results = obj["results"]?.jsonArray ?: return null
            if (results.isEmpty()) return null

            val first = results[0].jsonObject
            val title = first["title"]?.jsonPrimitive?.contentOrNull ?: first["original_title"]?.jsonPrimitive?.content ?: ""
            val overview = first["overview"]?.jsonPrimitive?.contentOrNull ?: ""
            val posterPath = first["poster_path"]?.jsonPrimitive?.contentOrNull
            val backdropPath = first["backdrop_path"]?.jsonPrimitive?.contentOrNull
            val releaseDate = first["release_date"]?.jsonPrimitive?.contentOrNull ?: ""
            val year = if (releaseDate.length >= 4) releaseDate.take(4) else null

            val poster = if (posterPath != null) "https://image.tmdb.org/t/p/w500$posterPath" else null
            val backdrop = if (backdropPath != null) "https://image.tmdb.org/t/p/w1280$backdropPath" else poster

            val meta = ScrapedMetadata(
                title = title,
                overview = overview,
                posterUrl = poster,
                backdropUrl = backdrop,
                releaseYear = year,
                genres = listOf("Movie")
            )

            Pair(meta, emptyMap())
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchTvMaze(encodedQuery: String): Pair<ScrapedMetadata?, Map<String, ScrapedEpisode>>? {
        return try {
            val url = "https://api.tvmaze.com/singlesearch/shows?q=$encodedQuery&embed=episodes"
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            val obj = json.parseToJsonElement(body).jsonObject

            val title = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val imageObj = obj["image"]?.jsonObject
            val poster = imageObj?.get("original")?.jsonPrimitive?.contentOrNull ?: imageObj?.get("medium")?.jsonPrimitive?.contentOrNull
            val premiered = obj["premiered"]?.jsonPrimitive?.contentOrNull ?: ""
            val year = if (premiered.length >= 4) premiered.take(4) else null

            val meta = ScrapedMetadata(
                title = title,
                overview = obj["summary"]?.jsonPrimitive?.contentOrNull?.replace(Regex("<.*?>"), "") ?: "",
                posterUrl = poster,
                backdropUrl = poster,
                releaseYear = year,
                genres = listOf("TV Series")
            )

            val episodesMap = mutableMapOf<String, ScrapedEpisode>()
            val embedded = obj["_embedded"]?.jsonObject
            val eps = embedded?.get("episodes")?.jsonArray ?: JsonArray(emptyList())
            for (epElem in eps) {
                val epObj = epElem.jsonObject
                val s = epObj["season"]?.jsonPrimitive?.intOrNull ?: 1
                val epNum = epObj["number"]?.jsonPrimitive?.intOrNull ?: 1
                val epName = epObj["name"]?.jsonPrimitive?.contentOrNull ?: "Episode $epNum"
                val epImage = epObj["image"]?.jsonObject?.get("original")?.jsonPrimitive?.contentOrNull ?: poster
                episodesMap["S${s}E${epNum}"] = ScrapedEpisode(
                    season = s,
                    episode = epNum,
                    title = epName,
                    overview = epObj["summary"]?.jsonPrimitive?.contentOrNull ?: "",
                    thumbnail = epImage
                )
            }

            Pair(meta, episodesMap)
        } catch (_: Exception) {
            null
        }
    }
}

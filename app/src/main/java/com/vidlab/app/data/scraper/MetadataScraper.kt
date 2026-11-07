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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun fetchDetailedMetadata(rawTitle: String): Pair<ScrapedMetadata?, Map<String, ScrapedEpisode>> = withContext(Dispatchers.IO) {
        val cleanName = FilenameSanitizer.parse(rawTitle).cleanTitle
        val encoded = URLEncoder.encode(cleanName, "UTF-8")

        // 1. Cinemeta series lookup
        val seriesResult = fetchCinemeta("series", encoded)
        if (seriesResult != null) return@withContext seriesResult

        // 2. Cinemeta movie lookup
        val movieResult = fetchCinemeta("movie", encoded)
        if (movieResult != null) return@withContext movieResult

        // 3. TVmaze fallback for anime/shows
        val tvMazeResult = fetchTvMaze(encoded)
        if (tvMazeResult != null) return@withContext tvMazeResult

        Pair(null, emptyMap())
    }

    private fun fetchCinemeta(type: String, query: String): Pair<ScrapedMetadata?, Map<String, ScrapedEpisode>>? {
        return try {
            val searchUrl = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$query.json"
            val req = Request.Builder().url(searchUrl).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            val metas = json.parseToJsonElement(body).jsonObject["metas"]?.jsonArray ?: return null
            if (metas.isEmpty()) return null

            val topItem = metas[0].jsonObject
            val imdbId = topItem["id"]?.jsonPrimitive?.contentOrNull ?: return null

            // Detailed meta fetch with episodes
            val detailUrl = "https://v3-cinemeta.strem.io/meta/$type/$imdbId.json"
            val detailResp = client.newCall(Request.Builder().url(detailUrl).build()).execute()
            val detailBody = detailResp.body?.string() ?: return null
            val metaObj = json.parseToJsonElement(detailBody).jsonObject["meta"]?.jsonObject ?: return null

            val title = metaObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
            val poster = metaObj["poster"]?.jsonPrimitive?.contentOrNull
            val backdrop = metaObj["background"]?.jsonPrimitive?.contentOrNull ?: poster
            val year = metaObj["year"]?.jsonPrimitive?.contentOrNull
            val genres = metaObj["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

            val meta = ScrapedMetadata(
                title = title,
                overview = metaObj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                posterUrl = poster,
                backdropUrl = backdrop,
                releaseYear = year,
                genres = genres
            )

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
                    overview = "",
                    thumbnail = epImage
                )
            }

            Pair(meta, episodesMap)
        } catch (_: Exception) {
            null
        }
    }
}

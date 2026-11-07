package com.vidlab.app.data.scraper

import com.vidlab.app.data.model.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ScrapedEpisode(
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val thumbnail: String? = null
)

class MetadataScraper(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val cinemetaBase = "https://v3-cinemeta.strem.io"

    suspend fun fetchDetailedMetadata(rawName: String): Pair<MediaMetadata?, Map<String, ScrapedEpisode>> = withContext(Dispatchers.IO) {
        val info = FilenameSanitizer.parse(rawName)
        var query = info.cleanTitle
        if (query.isBlank()) return@withContext Pair(null, emptyMap())

        if (query.contains("Iceblade", true)) {
            query = "The Iceblade Magician Shall Rule the World"
        }

        val primaryType = if (info.isSeries) "series" else "movie"
        var result = searchAndFetchDetail(query, primaryType, info)

        if (result.first == null) {
            val altType = if (primaryType == "series") "movie" else "series"
            result = searchAndFetchDetail(query, altType, info)
        }

        result
    }

    private fun searchAndFetchDetail(query: String, type: String, info: ParsedMediaInfo): Pair<MediaMetadata?, Map<String, ScrapedEpisode>> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$cinemetaBase/catalog/$type/top/search=$encodedQuery.json"
            val searchReq = Request.Builder().url(searchUrl).build()

            val body = client.newCall(searchReq).execute().use { res ->
                if (res.isSuccessful) res.body?.string() else null
            } ?: return Pair(null, emptyMap())

            val root = json.parseToJsonElement(body).jsonObject
            val metas = root["metas"]?.jsonArray ?: return Pair(null, emptyMap())
            if (metas.isEmpty()) return Pair(null, emptyMap())

            val bestMatch = metas.firstOrNull { elem ->
                val yearStr = elem.jsonObject["year"]?.jsonPrimitive?.content ?: ""
                info.year == null || yearStr.startsWith(info.year.toString())
            }?.jsonObject ?: metas[0].jsonObject

            val imdbId = bestMatch["id"]?.jsonPrimitive?.content ?: ""
            if (imdbId.isBlank()) return Pair(null, emptyMap())

            val detailUrl = "$cinemetaBase/meta/$type/$imdbId.json"
            val detailReq = Request.Builder().url(detailUrl).build()
            val detailBody = client.newCall(detailReq).execute().use { res ->
                if (res.isSuccessful) res.body?.string() else null
            } ?: return Pair(parseBasicMeta(bestMatch, info), emptyMap())

            val detailMeta = json.parseToJsonElement(detailBody).jsonObject["meta"]?.jsonObject ?: bestMatch
            val parsedMeta = parseBasicMeta(detailMeta, info)

            val epMap = mutableMapOf<String, ScrapedEpisode>()
            val videos = detailMeta["videos"]?.jsonArray
            videos?.forEach { v ->
                val vObj = v.jsonObject
                val sNum = vObj["season"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                val eNum = vObj["episode"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                val epTitle = vObj["title"]?.jsonPrimitive?.content ?: "Episode $eNum"
                val epThumb = vObj["thumbnail"]?.jsonPrimitive?.content

                epMap["S${sNum}E${eNum}"] = ScrapedEpisode(sNum, eNum, epTitle, epThumb)
            }

            Pair(parsedMeta, epMap)
        } catch (_: Exception) {
            Pair(null, emptyMap())
        }
    }

    private fun parseBasicMeta(obj: JsonObject, info: ParsedMediaInfo): MediaMetadata {
        return MediaMetadata(
            id = obj["id"]?.jsonPrimitive?.content ?: "",
            title = obj["name"]?.jsonPrimitive?.content ?: info.cleanTitle,
            posterUrl = obj["poster"]?.jsonPrimitive?.content,
            backdropUrl = obj["background"]?.jsonPrimitive?.content ?: obj["poster"]?.jsonPrimitive?.content,
            description = obj["description"]?.jsonPrimitive?.content,
            releaseYear = obj["year"]?.jsonPrimitive?.content ?: info.year?.toString(),
            genres = obj["genres"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            rating = obj["imdbRating"]?.jsonPrimitive?.content
        )
    }
}

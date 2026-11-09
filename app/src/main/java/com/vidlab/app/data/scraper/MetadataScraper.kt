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

        try {
            val tvMazeUrl = "https://api.tvmaze.com/search/shows?q=$encoded"
            val resp = client.newCall(Request.Builder().url(tvMazeUrl).build()).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val array = json.parseToJsonElement(body).jsonArray
                for (item in array.take(4)) {
                    val show = item.jsonObject["show"]?.jsonObject ?: continue
                    val id = show["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val name = show["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val premiered = show["premiered"]?.jsonPrimitive?.contentOrNull ?: ""
                    val year = if (premiered.length >= 4) premiered.take(4) else null
                    val poster = show["image"]?.jsonObject?.get("medium")?.jsonPrimitive?.contentOrNull
                    results.add(TmdbSearchResult(id, name, "tv", year, poster))
                }
            }
        } catch (_: Exception) {}

        try {
            val omdbUrl = "https://www.omdbapi.com/?s=$encoded&apikey=trilogy"
            val resp = client.newCall(Request.Builder().url(omdbUrl).build()).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val obj = json.parseToJsonElement(body).jsonObject
                val search = obj["Search"]?.jsonArray ?: JsonArray(emptyList())
                for (item in search.take(4)) {
                    val o = item.jsonObject
                    val title = o["Title"]?.jsonPrimitive?.contentOrNull ?: continue
                    val year = o["Year"]?.jsonPrimitive?.contentOrNull
                    val poster = o["Poster"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "N/A" }
                    val imdbId = o["imdbID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val idNum = imdbId.filter { it.isDigit() }.toIntOrNull() ?: imdbId.hashCode()
                    results.add(TmdbSearchResult(idNum, title, "movie", year, poster))
                }
            }
        } catch (_: Exception) {}

        results
    }

    suspend fun scrapeWithVerification(rawTitle: String, forcedId: String? = null, forcedType: String? = null): MatchResult = withContext(Dispatchers.IO) {
        val parsed = FilenameSanitizer.parse(rawTitle)
        val clean = parsed.cleanTitle
        val encoded = URLEncoder.encode(clean, "UTF-8")

        // 1. TVmaze Show lookup
        try {
            val tvMazeUrl = "https://api.tvmaze.com/singlesearch/shows?q=$encoded&embed=episodes"
            val resp = client.newCall(Request.Builder().url(tvMazeUrl).build()).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val obj = json.parseToJsonElement(body).jsonObject
                val title = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val premiered = obj["premiered"]?.jsonPrimitive?.contentOrNull ?: ""
                val year = if (premiered.length >= 4) premiered.take(4) else null

                val imgObj = obj["image"]?.jsonObject
                val poster = imgObj?.get("original")?.jsonPrimitive?.contentOrNull ?: imgObj?.get("medium")?.jsonPrimitive?.contentOrNull

                val episodesMap = mutableMapOf<String, ScrapedEpisode>()
                val epsArray = obj["_embedded"]?.jsonObject?.get("episodes")?.jsonArray ?: JsonArray(emptyList())

                for (epElem in epsArray) {
                    val eo = epElem.jsonObject
                    val s = eo["season"]?.jsonPrimitive?.intOrNull ?: 1
                    val epNum = eo["number"]?.jsonPrimitive?.intOrNull ?: 1
                    val epName = eo["name"]?.jsonPrimitive?.contentOrNull ?: "Episode $epNum"
                    val epThumb = eo["image"]?.jsonObject?.get("original")?.jsonPrimitive?.contentOrNull ?: poster

                    episodesMap["S${s}E${epNum}"] = ScrapedEpisode(s, epNum, epName, "", epThumb)
                }

                val meta = MediaMetadata(title, poster, poster, year, listOf("TV Series"))
                return@withContext MatchResult.ConfidentMatch(meta, episodesMap)
            }
        } catch (_: Exception) {}

        // 2. Movie lookup via OMDb
        try {
            val yrParam = if (!parsed.year.isNullOrBlank()) "&y=${parsed.year}" else ""
            val omdbUrl = "https://www.omdbapi.com/?t=$encoded$yrParam&apikey=trilogy"
            val resp = client.newCall(Request.Builder().url(omdbUrl).build()).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val obj = json.parseToJsonElement(body).jsonObject
                if (obj["Response"]?.jsonPrimitive?.contentOrNull == "True") {
                    val title = obj["Title"]?.jsonPrimitive?.contentOrNull ?: clean
                    val year = obj["Year"]?.jsonPrimitive?.contentOrNull
                    val poster = obj["Poster"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "N/A" }
                    val meta = MediaMetadata(title, poster, poster, year, listOf("Movie"))
                    return@withContext MatchResult.ConfidentMatch(meta, emptyMap())
                }
            }
        } catch (_: Exception) {}

        MatchResult.NoMatch
    }
}

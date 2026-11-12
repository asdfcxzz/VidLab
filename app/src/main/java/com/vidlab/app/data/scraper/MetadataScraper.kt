package com.vidlab.app.data.scraper

import com.vidlab.app.data.model.CastMember
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
    private val tmdbApiKey = "b7cd3340a794e5a2f35e3abb820b497f"
    private val imageBase = "https://image.tmdb.org/t/p/w500"
    private val backdropBase = "https://image.tmdb.org/t/p/w1280"
    private val profileBase = "https://image.tmdb.org/t/p/w185"

    suspend fun searchMetadata(query: String): List<TmdbSearchResult> = withContext(Dispatchers.IO) {
        val clean = FilenameSanitizer.parse(query).cleanTitle.ifEmpty { query }
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val results = mutableListOf<TmdbSearchResult>()

        try {
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$tmdbApiKey&query=$encoded&include_adult=false"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val array = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: JsonArray(emptyList())
                for (elem in array.take(12)) {
                    val o = elem.jsonObject
                    val mediaType = o["media_type"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (mediaType != "movie" && mediaType != "tv") continue

                    val id = o["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val title = if (mediaType == "movie") {
                        o["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    } else {
                        o["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    }

                    val date = if (mediaType == "movie") {
                        o["release_date"]?.jsonPrimitive?.contentOrNull ?: ""
                    } else {
                        o["first_air_date"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    val year = if (date.length >= 4) date.take(4) else null
                    val posterPath = o["poster_path"]?.jsonPrimitive?.contentOrNull
                    val poster = if (posterPath != null) "$imageBase$posterPath" else null

                    results.add(TmdbSearchResult(id, title, mediaType, year, poster))
                }
            }
            resp.close()
        } catch (_: Exception) {}

        results
    }

    suspend fun scrapeWithVerification(
        rawTitle: String,
        isKnownSeries: Boolean,
        forcedId: String? = null,
        forcedType: String? = null,
        forcedYear: String? = null
    ): MatchResult = withContext(Dispatchers.IO) {
        val parsed = FilenameSanitizer.parse(rawTitle, forceMovie = !isKnownSeries && forcedType != "tv")
        val clean = parsed.cleanTitle.ifBlank { rawTitle }
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val searchType = if (isKnownSeries || forcedType == "tv") "tv" else "movie"

        if (forcedId != null && forcedId.all { it.isDigit() }) {
            val details = fetchTmdbDetails(forcedId.toInt(), searchType)
            if (details != null) {
                return@withContext MatchResult.ConfidentMatch(details.first, details.second)
            }
        }

        val candidates = mutableListOf<TmdbSearchResult>()
        try {
            val yearParam = if (!forcedYear.isNullOrBlank()) {
                if (searchType == "movie") "&year=$forcedYear" else "&first_air_date_year=$forcedYear"
            } else if (!parsed.year.isNullOrBlank()) {
                if (searchType == "movie") "&year=${parsed.year}" else "&first_air_date_year=${parsed.year}"
            } else ""

            val url = "https://api.themoviedb.org/3/search/$searchType?api_key=$tmdbApiKey&query=$encoded$yearParam&include_adult=false"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val array = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: JsonArray(emptyList())
                for (elem in array.take(8)) {
                    val o = elem.jsonObject
                    val id = o["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val title = if (searchType == "movie") o["title"]?.jsonPrimitive?.contentOrNull ?: "" else o["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val date = if (searchType == "movie") o["release_date"]?.jsonPrimitive?.contentOrNull ?: "" else o["first_air_date"]?.jsonPrimitive?.contentOrNull ?: ""
                    val year = if (date.length >= 4) date.take(4) else null
                    val posterPath = o["poster_path"]?.jsonPrimitive?.contentOrNull
                    val poster = if (posterPath != null) "$imageBase$posterPath" else null
                    candidates.add(TmdbSearchResult(id, title, searchType, year, poster))
                }
            }
            resp.close()
        } catch (_: Exception) {}

        if (candidates.isEmpty()) {
            try {
                val url = "https://api.themoviedb.org/3/search/$searchType?api_key=$tmdbApiKey&query=$encoded&include_adult=false"
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val array = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: JsonArray(emptyList())
                    for (elem in array.take(8)) {
                        val o = elem.jsonObject
                        val id = o["id"]?.jsonPrimitive?.intOrNull ?: continue
                        val title = if (searchType == "movie") o["title"]?.jsonPrimitive?.contentOrNull ?: "" else o["name"]?.jsonPrimitive?.contentOrNull ?: ""
                        val date = if (searchType == "movie") o["release_date"]?.jsonPrimitive?.contentOrNull ?: "" else o["first_air_date"]?.jsonPrimitive?.contentOrNull ?: ""
                        val year = if (date.length >= 4) date.take(4) else null
                        val posterPath = o["poster_path"]?.jsonPrimitive?.contentOrNull
                        val poster = if (posterPath != null) "$imageBase$posterPath" else null
                        candidates.add(TmdbSearchResult(id, title, searchType, year, poster))
                    }
                }
                resp.close()
            } catch (_: Exception) {}
        }

        if (candidates.isNotEmpty()) {
            val normQuery = clean.lowercase().replace(Regex("[^a-z0-9]"), "")
            val exactCandidate = candidates.firstOrNull {
                it.title.lowercase().replace(Regex("[^a-z0-9]"), "") == normQuery
            } ?: candidates.first()

            val details = fetchTmdbDetails(exactCandidate.id, searchType)
            if (details != null) {
                if (searchType == "movie" || details.second.isNotEmpty()) {
                    return@withContext MatchResult.ConfidentMatch(details.first, details.second)
                }
            }

            if (candidates.size > 1) {
                return@withContext MatchResult.Ambiguous(clean, candidates)
            }
        }

        MatchResult.NoMatch
    }

    private suspend fun fetchTmdbDetails(id: Int, type: String): Pair<MediaMetadata, Map<String, ScrapedEpisode>>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.themoviedb.org/3/$type/$id?api_key=$tmdbApiKey&append_to_response=credits"
            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            if (!resp.isSuccessful) {
                resp.close()
                return@withContext null
            }

            val body = resp.body?.string() ?: ""
            resp.close()
            val obj = json.parseToJsonElement(body).jsonObject

            val title = if (type == "movie") {
                obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
            } else {
                obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
            }

            val date = if (type == "movie") {
                obj["release_date"]?.jsonPrimitive?.contentOrNull ?: ""
            } else {
                obj["first_air_date"]?.jsonPrimitive?.contentOrNull ?: ""
            }
            val year = if (date.length >= 4) date.take(4) else null

            val rating = obj["vote_average"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 }
            val overview = obj["overview"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

            val posterPath = obj["poster_path"]?.jsonPrimitive?.contentOrNull
            val backdropPath = obj["backdrop_path"]?.jsonPrimitive?.contentOrNull
            val poster = if (posterPath != null) "$imageBase$posterPath" else null
            val backdrop = if (backdropPath != null) "$backdropBase$backdropPath" else poster

            val genres = obj["genres"]?.jsonArray?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            } ?: listOf(if (type == "movie") "Movie" else "TV Series")

            val castList = mutableListOf<CastMember>()
            val creditsObj = obj["credits"]?.jsonObject
            if (creditsObj != null) {
                val crewArr = creditsObj["crew"]?.jsonArray ?: JsonArray(emptyList())
                val director = crewArr.firstOrNull {
                    it.jsonObject["job"]?.jsonPrimitive?.contentOrNull.equals("Director", ignoreCase = true)
                }?.jsonObject
                if (director != null) {
                    val dName = director["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val dPath = director["profile_path"]?.jsonPrimitive?.contentOrNull
                    if (dName.isNotBlank()) {
                        castList.add(CastMember(dName, "Director", dPath?.let { "$profileBase$it" }))
                    }
                }

                val castArr = creditsObj["cast"]?.jsonArray ?: JsonArray(emptyList())
                for (c in castArr.take(12)) {
                    val co = c.jsonObject
                    val cName = co["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val cRole = co["character"]?.jsonPrimitive?.contentOrNull ?: ""
                    val cPath = co["profile_path"]?.jsonPrimitive?.contentOrNull
                    castList.add(CastMember(cName, cRole, cPath?.let { "$profileBase$it" }))
                }
            }

            val meta = MediaMetadata(
                title = title,
                posterUrl = poster,
                backdropUrl = backdrop,
                releaseYear = year,
                genres = genres,
                rating = rating,
                overview = overview,
                cast = castList
            )
            val episodesMap = mutableMapOf<String, ScrapedEpisode>()

            if (type == "tv") {
                val seasons = obj["seasons"]?.jsonArray ?: JsonArray(emptyList())
                for (s in seasons) {
                    val sNum = s.jsonObject["season_number"]?.jsonPrimitive?.intOrNull ?: continue
                    if (sNum <= 0) continue

                    try {
                        val sUrl = "https://api.themoviedb.org/3/tv/$id/season/$sNum?api_key=$tmdbApiKey"
                        val sResp = client.newCall(Request.Builder().url(sUrl).build()).execute()
                        if (sResp.isSuccessful) {
                            val sBody = sResp.body?.string() ?: ""
                            val eps = json.parseToJsonElement(sBody).jsonObject["episodes"]?.jsonArray ?: JsonArray(emptyList())
                            for (ep in eps) {
                                val eo = ep.jsonObject
                                val eNum = eo["episode_number"]?.jsonPrimitive?.intOrNull ?: continue
                                val eName = eo["name"]?.jsonPrimitive?.contentOrNull ?: "Episode $eNum"
                                val still = eo["still_path"]?.jsonPrimitive?.contentOrNull
                                val thumb = if (still != null) "$imageBase$still" else backdrop

                                episodesMap["S${sNum}E${eNum}"] = ScrapedEpisode(sNum, eNum, eName, "", thumb)
                            }
                        }
                        sResp.close()
                    } catch (_: Exception) {}
                }
            }

            Pair(meta, episodesMap)
        } catch (_: Exception) {
            null
        }
    }
}

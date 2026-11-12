package com.vidlab.app.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SimklPinSession(
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val interval: Int
)

class SimklManager(context: Context) {
    private val prefs = context.getSharedPreferences("vidlab_simkl_prefs", Context.MODE_PRIVATE)

    companion object {
        const val CLIENT_ID = "ce788a7f345379c564a29aab6396783a0cde42a59c44d2a3658d4bb859740634"
        private const val BASE_URL = "https://api.simkl.com"
    }

    var accessToken: String?
        get() = prefs.getString("simkl_access_token", null)
        set(value) = prefs.edit().putString("simkl_access_token", value).apply()

    var username: String?
        get() = prefs.getString("simkl_username", null)
        set(value) = prefs.edit().putString("simkl_username", value).apply()

    var scrobbleEnabled: Boolean
        get() = prefs.getBoolean("simkl_scrobble_enabled", true)
        set(value) = prefs.edit().putBoolean("simkl_scrobble_enabled", value).apply()

    val isSignedIn: Boolean
        get() = !accessToken.isNullOrBlank()

    fun signOut() {
        prefs.edit().clear().apply()
    }

    suspend fun requestPinCode(): Result<SimklPinSession> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("$BASE_URL/oauth/pin?client_id=$CLIENT_ID")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            SimklPinSession(
                userCode = json.getString("user_code"),
                verificationUrl = json.optString("verification_url", "https://simkl.com/pin"),
                expiresIn = json.optInt("expires_in", 900),
                interval = json.optInt("interval", 5)
            )
        }
    }

    suspend fun pollPinStatus(userCode: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("$BASE_URL/oauth/pin/$userCode?client_id=$CLIENT_ID")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val result = json.optString("result", "")
            val token = json.optString("access_token", "")
            if (result == "OK" && token.isNotBlank()) {
                accessToken = token
                fetchUserSettings(token)
                true
            } else {
                false
            }
        }
    }

    private fun fetchUserSettings(token: String) {
        runCatching {
            val url = URL("$BASE_URL/users/settings")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("simkl-api-key", CLIENT_ID)
                doOutput = true
            }
            conn.outputStream.use { it.write("{}".toByteArray()) }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val userObj = json.optJSONObject("user")
            val name = userObj?.optString("name")?.takeIf { it.isNotBlank() } ?: "Simkl Member"
            username = name
        }
    }

    suspend fun scrobblePlayback(rawTitleOrFilename: String) = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext
        if (!scrobbleEnabled || token.isBlank()) return@withContext

        runCatching {
            val cleanName = rawTitleOrFilename
                .substringAfterLast("/")
                .substringBeforeLast(".")
                .replace('.', ' ')
                .replace('_', ' ')
                .trim()

            val epRegex = Regex("""(?i)(.*?)\s*[-–]?\s*S(\d{1,2})E(\d{1,3})""")
            val epMatch = epRegex.find(cleanName)

            val payload = JSONObject()
            if (epMatch != null) {
                val showTitle = epMatch.groupValues[1].trim()
                val seasonNum = epMatch.groupValues[2].toIntOrNull() ?: 1
                val episodeNum = epMatch.groupValues[3].toIntOrNull() ?: 1

                val showObj = JSONObject().apply {
                    put("title", showTitle)
                    put("seasons", JSONArray().put(JSONObject().apply {
                        put("number", seasonNum)
                        put("episodes", JSONArray().put(JSONObject().apply {
                            put("number", episodeNum)
                        }))
                    }))
                }
                payload.put("shows", JSONArray().put(showObj))
            } else {
                val movieTitle = cleanName.replace(Regex("""\(\d{4}\).*"""), "").trim()
                val movieObj = JSONObject().apply {
                    put("title", movieTitle)
                }
                payload.put("movies", JSONArray().put(movieObj))
            }

            val url = URL("$BASE_URL/sync/history")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("simkl-api-key", CLIENT_ID)
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            conn.responseCode
        }
    }
}

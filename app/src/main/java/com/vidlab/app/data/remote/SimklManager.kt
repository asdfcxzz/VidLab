package com.vidlab.app.data.remote

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

data class SimklPinSession(
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val interval: Int,
    val deviceCode: String? = null,
    val codeVerifier: String? = null,
    val isV2: Boolean = false
)

class SimklManager(context: Context) {
    private val prefs = context.getSharedPreferences("vidlab_simkl_prefs", Context.MODE_PRIVATE)

    companion object {
        const val CLIENT_ID = "ce788a7f345379c564a29aab6396783a0cde42a59c44d2a3658d4bb859740634"
        private const val BASE_URL = "https://api.simkl.com"
        private const val USER_AGENT = "VidLab/2.4 (Android)"
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

    private fun readStream(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    private fun generatePkcePair(): Pair<String, String> {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val verifier = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return verifier to challenge
    }

    suspend fun requestPinCode(): Result<SimklPinSession> = withContext(Dispatchers.IO) {
        runCatching {
            // Try AUTH V1 /oauth/pin first
            val v1Url = URL("$BASE_URL/oauth/pin?client_id=$CLIENT_ID")
            val v1Conn = (v1Url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("simkl-api-key", CLIENT_ID)
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            val (v1Code, v1Body) = readStream(v1Conn)
            if (v1Code in 200..299) {
                val json = JSONObject(v1Body)
                return@runCatching SimklPinSession(
                    userCode = json.getString("user_code"),
                    verificationUrl = json.optString("verification_url", "https://simkl.com/pin"),
                    expiresIn = json.optInt("expires_in", 900),
                    interval = json.optInt("interval", 5),
                    isV2 = false
                )
            }

            // Fallback to AUTH V2 /oauth2/device with PKCE S256 if V1 is rejected
            val (verifier, challenge) = generatePkcePair()
            val v2Url = URL("$BASE_URL/oauth2/device")
            val v2Conn = (v2Url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("simkl-api-key", CLIENT_ID)
                connectTimeout = 12_000
                readTimeout = 12_000
                doOutput = true
            }
            val reqJson = JSONObject().apply {
                put("client_id", CLIENT_ID)
                put("code_challenge", challenge)
                put("code_challenge_method", "S256")
                put("scope", "media:read media:write")
            }
            v2Conn.outputStream.use { it.write(reqJson.toString().toByteArray()) }
            val (v2Code, v2Body) = readStream(v2Conn)
            if (v2Code in 200..299) {
                val json = JSONObject(v2Body)
                return@runCatching SimklPinSession(
                    userCode = json.getString("user_code"),
                    verificationUrl = json.optString("verification_uri", "https://simkl.com/pin"),
                    expiresIn = json.optInt("expires_in", 900),
                    interval = json.optInt("interval", 5),
                    deviceCode = json.optString("device_code"),
                    codeVerifier = verifier,
                    isV2 = true
                )
            }

            throw IllegalStateException("HTTP $v1Code / $v2Code:${v2Body.ifBlank { v1Body }}")
        }
    }

    suspend fun pollPinStatus(session: SimklPinSession): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            if (!session.isV2) {
                val url = URL("$BASE_URL/oauth/pin/${session.userCode}?client_id=$CLIENT_ID")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("simkl-api-key", CLIENT_ID)
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val (code, body) = readStream(conn)
                if (code !in 200..299) return@runCatching false
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
            } else {
                val url = URL("$BASE_URL/oauth2/token")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("simkl-api-key", CLIENT_ID)
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                }
                val reqJson = JSONObject().apply {
                    put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    put("client_id", CLIENT_ID)
                    put("device_code", session.deviceCode ?: "")
                    put("code_verifier", session.codeVerifier ?: "")
                }
                conn.outputStream.use { it.write(reqJson.toString().toByteArray()) }
                val (code, body) = readStream(conn)
                if (code !in 200..299) return@runCatching false
                val json = JSONObject(body)
                val token = json.optString("access_token", "")
                if (token.isNotBlank()) {
                    accessToken = token
                    fetchUserSettings(token)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun fetchUserSettings(token: String) {
        runCatching {
            val url = URL("$BASE_URL/users/settings")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("simkl-api-key", CLIENT_ID)
            }
            val (code, body) = readStream(conn)
            if (code in 200..299) {
                val json = JSONObject(body)
                val userObj = json.optJSONObject("user")
                val name = userObj?.optString("name")?.takeIf { it.isNotBlank() } ?: "Simkl Member"
                username = name
            }
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
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
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

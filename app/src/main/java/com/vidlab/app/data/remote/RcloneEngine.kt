package com.vidlab.app.data.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import com.vidlab.app.data.model.MediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Serializable
data class RcloneRemoteConfig(
    val name: String,
    val type: String,
    val rootSubpath: String = "",
    val enabled: Boolean = true
)

@Serializable
data class RcloneLsJsonEntry(
    val Path: String = "",
    val Name: String = "",
    val Size: Long = 0L,
    val IsDir: Boolean = false
)

object RcloneEngine {
    const val STREAM_PORT = 18080
    const val STREAM_BASE_URL = "http://127.0.0.1:$STREAM_PORT"

    private const val PREFS_NAME = "vidlab_rclone_prefs"
    private const val KEY_REMOTES_JSON = "configured_remotes_json"
    private const val KEY_USE_RCLONE = "use_embedded_rclone"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Volatile
    private var serverProcess: Process? = null

    @Volatile
    private var activeServedConfigSig: String = ""

    fun getBinaryFile(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir, "librclone.so")
    }

    fun getConfigFile(context: Context): File {
        return File(context.filesDir, "rclone.conf")
    }

    fun getRuntimeServeConfigFile(context: Context): File {
        return File(context.filesDir, "rclone_serve.conf")
    }

    fun isRcloneModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_USE_RCLONE, getConfigFile(context).exists())
    }

    fun setRcloneModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_RCLONE, enabled).apply()
    }

    fun hasValidConfig(context: Context): Boolean {
        val f = getConfigFile(context)
        return f.exists() && parseRemotesFromConf(f.readText()).isNotEmpty()
    }

    fun parseRemotesFromConf(confText: String): List<Pair<String, String>> {
        val remotes = mutableListOf<Pair<String, String>>()
        var currentSection: String? = null
        var currentType = "remote"
        for (rawLine in confText.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("[") && line.endsWith("]") && line.length > 2) {
                if (currentSection != null) {
                    remotes.add(currentSection to currentType)
                }
                currentSection = line.substring(1, line.length - 1).trim()
                currentType = "remote"
            } else if (currentSection != null && line.startsWith("type")) {
                val eqIdx = line.indexOf('=')
                if (eqIdx > 0) {
                    currentType = line.substring(eqIdx + 1).trim()
                }
            }
        }
        if (currentSection != null) {
            remotes.add(currentSection to currentType)
        }
        return remotes
    }

    fun importConfigText(context: Context, confText: String): Result<List<RcloneRemoteConfig>> {
        return runCatching {
            val parsed = parseRemotesFromConf(confText)
            require(parsed.isNotEmpty()) { "No [remote] sections found in rclone.conf" }
            getConfigFile(context).writeText(confText.trim() + "\n")

            val existing = getConfiguredRemotes(context).associateBy { it.name }
            // Prefer 'vault' or 'decrypted' enabled by default if present, otherwise enable first remote
            val hasPreferred = parsed.any { it.first.equals("vault", true) || it.first.equals("decrypted", true) }
            val updated = parsed.mapIndexed { idx, (name, type) ->
                val prev = existing[name]
                if (prev != null) {
                    prev.copy(type = type)
                } else {
                    val autoEnable = if (hasPreferred) {
                        name.equals("vault", true) || name.equals("decrypted", true)
                    } else {
                        idx == 0
                    }
                    RcloneRemoteConfig(name = name, type = type, rootSubpath = "", enabled = autoEnable)
                }
            }
            saveConfiguredRemotes(context, updated)
            setRcloneModeEnabled(context, true)
            restartServeIfNeeded(context)
            updated
        }
    }

    fun getConfiguredRemotes(context: Context): List<RcloneRemoteConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_REMOTES_JSON, null)
        if (!raw.isNullOrBlank()) {
            val saved = runCatching { json.decodeFromString<List<RcloneRemoteConfig>>(raw) }.getOrNull()
            if (!saved.isNullOrEmpty()) return saved
        }
        val confFile = getConfigFile(context)
        if (!confFile.exists()) return emptyList()
        return parseRemotesFromConf(confFile.readText()).mapIndexed { idx, (name, type) ->
            RcloneRemoteConfig(name, type, "", idx == 0)
        }
    }

    fun saveConfiguredRemotes(context: Context, remotes: List<RcloneRemoteConfig>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_REMOTES_JSON, json.encodeToString(remotes)).apply()
    }

    /**
     * Builds a runtime rclone config containing a unified [vidlab_mount] combine remote
     * mapping every enabled remote so `/RemoteName/...` or single-remote root paths resolve seamlessly.
     */
    private fun prepareRuntimeServeTarget(context: Context): String? {
        val baseConf = getConfigFile(context)
        if (!baseConf.exists()) return null
        val enabledRemotes = getConfiguredRemotes(context).filter { it.enabled }
        if (enabledRemotes.isEmpty()) return null

        val runtimeConf = getRuntimeServeConfigFile(context)
        val sb = StringBuilder(baseConf.readText().trim()).append("\n\n")

        if (enabledRemotes.size == 1) {
            val r = enabledRemotes.first()
            val sub = r.rootSubpath.trim('/')
            runtimeConf.writeText(sb.toString())
            return if (sub.isEmpty()) "${r.name}:" else "${r.name}:$sub"
        } else {
            val upstreams = enabledRemotes.joinToString(" ") { r ->
                val sub = r.rootSubpath.trim('/')
                val target = if (sub.isEmpty()) "${r.name}:" else "${r.name}:$sub"
                "${r.name}=$target"
            }
            sb.append("[vidlab_combined]\n")
            sb.append("type = combine\n")
            sb.append("upstreams = $upstreams\n")
            runtimeConf.writeText(sb.toString())
            return "vidlab_combined:"
        }
    }

    suspend fun scanEnabledRemotes(context: Context): Result<List<MediaFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val bin = getBinaryFile(context)
            require(bin.exists()) { "Embedded rclone binary missing" }
            val baseConf = getConfigFile(context)
            require(baseConf.exists()) { "Import rclone.conf first in the menu" }

            val enabledRemotes = getConfiguredRemotes(context).filter { it.enabled }
            require(enabledRemotes.isNotEmpty()) { "Select at least one rclone remote in the menu" }

            val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "flv")
            val results = mutableListOf<MediaFile>()
            val multiRemote = enabledRemotes.size > 1

            for (remote in enabledRemotes) {
                val sub = remote.rootSubpath.trim('/')
                val target = if (sub.isEmpty()) "${remote.name}:" else "${remote.name}:$sub"

                val cmd = listOf(
                    bin.absolutePath,
                    "lsjson",
                    "--config", baseConf.absolutePath,
                    "--recursive",
                    "--files-only",
                    "--no-modtime",
                    "--no-mimetype",
                    target
                )
                val proc = ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start()

                val stdout = proc.inputStream.bufferedReader().use { it.readText() }
                val stderr = proc.errorStream.bufferedReader().use { it.readText() }
                val exitCode = proc.waitFor()

                if (exitCode != 0 && stdout.isBlank()) {
                    throw Exception("rclone (${remote.name}): ${stderr.lines().lastOrNull { it.isNotBlank() } ?: "exit $exitCode"}")
                }

                val entries = runCatching { json.decodeFromString<List<RcloneLsJsonEntry>>(stdout) }.getOrDefault(emptyList())
                for (entry in entries) {
                    if (entry.IsDir) continue
                    val ext = entry.Name.substringAfterLast('.', "").lowercase()
                    if (ext !in videoExtensions) continue

                    val relPath = entry.Path.trim('/')
                    val logicalPath = if (multiRemote) {
                        "/${remote.name}/$relPath"
                    } else {
                        "/$relPath"
                    }
                    results.add(
                        MediaFile(
                            name = entry.Name,
                            path = logicalPath,
                            isDirectory = false,
                            size = entry.Size,
                            isVideo = true
                        )
                    )
                }
            }
            results
        }
    }

    fun startForegroundServeService(context: Context) {
        if (!isRcloneModeEnabled(context) || !hasValidConfig(context)) return
        val intent = Intent(context, RcloneForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun restartServeIfNeeded(context: Context) {
        stopServer()
        if (isRcloneModeEnabled(context) && hasValidConfig(context)) {
            startForegroundServeService(context)
        }
    }

    suspend fun ensureServerRunning(context: Context): Boolean = withContext(Dispatchers.IO) {
        val bin = getBinaryFile(context)
        if (!bin.exists()) return@withContext false
        val target = prepareRuntimeServeTarget(context) ?: return@withContext false
        val runtimeConf = getRuntimeServeConfigFile(context)
        val sig = "$target|${runtimeConf.lastModified()}"

        if (serverProcess?.isAlive == true && activeServedConfigSig == sig && isPortReachable()) {
            return@withContext true
        }

        stopServer()

        val cmd = listOf(
            bin.absolutePath,
            "serve", "webdav",
            target,
            "--config", runtimeConf.absolutePath,
            "--addr", "127.0.0.1:$STREAM_PORT",
            "--vfs-cache-mode", "minimal",
            "--vfs-read-chunk-size", "16M",
            "--vfs-read-chunk-size-limit", "128M",
            "--buffer-size", "16M",
            "--dir-cache-time", "30s",
            "--no-modtime"
        )

        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        serverProcess = proc
        activeServedConfigSig = sig

        // Drain output asynchronously so pipe buffer never blocks
        Thread {
            runCatching { proc.inputStream.bufferedReader().forEachLine { } }
        }.start()

        repeat(25) {
            if (isPortReachable()) return@withContext true
            delay(120)
        }
        isPortReachable()
    }

    fun stopServer() {
        runCatching { serverProcess?.destroy() }
        serverProcess = null
        activeServedConfigSig = ""
    }

    private fun isPortReachable(): Boolean {
        return runCatching {
            val conn = (URL("$STREAM_BASE_URL/").openConnection() as HttpURLConnection).apply {
                requestMethod = "OPTIONS"
                connectTimeout = 400
                readTimeout = 400
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        }.getOrDefault(false)
    }

    fun buildStreamUrl(context: Context, logicalPath: String): String {
        if (logicalPath.startsWith("http://") || logicalPath.startsWith("https://")) {
            return logicalPath
        }
        val base = if (isRcloneModeEnabled(context) && hasValidConfig(context)) {
            STREAM_BASE_URL
        } else {
            "http://127.0.0.1:8080"
        }
        val encodedPath = logicalPath.split('/').joinToString("/") { seg ->
            if (seg.isEmpty()) "" else URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        return "$base$encodedPath"
    }
}

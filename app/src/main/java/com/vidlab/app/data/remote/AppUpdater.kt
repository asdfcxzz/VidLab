package com.vidlab.app.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val body: String,
    val apkDownloadUrl: String,
    val isNewer: Boolean
)

object AppUpdater {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/asdfcxzz/VidLab/releases/latest"

    fun getCurrentVersionName(context: Context): String {
        return runCatching {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.1.4"
        }.getOrDefault("1.1.4")
    }

    suspend fun checkForUpdate(context: Context): Result<AppReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "VidLab-Android")
                connectTimeout = 12000
                readTimeout = 12000
            }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            if (code !in 200..299) {
                throw IllegalStateException("GitHub API returned HTTP $code")
            }

            val json = JSONObject(raw)
            val tag = json.optString("tag_name", "").trim()
            val cleanRemote = tag.removePrefix("v").removePrefix("V").trim()
            val body = json.optString("body", "").trim()
            val assets = json.optJSONArray("assets")

            var releaseApkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    val url = asset.optString("browser_download_url", "")
                    if (name.equals("app-release.apk", ignoreCase = true) && url.isNotBlank()) {
                        releaseApkUrl = url
                        break
                    }
                    if (releaseApkUrl.isBlank() && name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                        releaseApkUrl = url
                    }
                }
            }

            if (releaseApkUrl.isBlank()) {
                throw IllegalStateException("No APK asset attached to release $tag")
            }

            val currentVersion = getCurrentVersionName(context)
            val newer = isRemoteVersionNewer(currentVersion, cleanRemote)

            AppReleaseInfo(
                tagName = tag.ifBlank { "v$cleanRemote" },
                versionName = cleanRemote,
                body = body,
                apkDownloadUrl = releaseApkUrl,
                isNewer = newer
            )
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "app-release.apk")
            if (apkFile.exists()) apkFile.delete()

            val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VidLab-Android")
                connectTimeout = 15000
                readTimeout = 30000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("Download failed (HTTP $code)")
            }

            val totalBytes = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (totalBytes > 0L) {
                            withContext(Dispatchers.Main) {
                                onProgress((downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            conn.disconnect()

            withContext(Dispatchers.Main) {
                onProgress(1f)
                launchApkInstaller(context, apkFile)
            }
        }
    }

    private fun launchApkInstaller(context: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }

    private fun isRemoteVersionNewer(current: String, remote: String): Boolean {
        val curParts = current.split(".").mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        val remParts = remote.split(".").mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        val maxLen = maxOf(curParts.size, remParts.size)
        for (i in 0 until maxLen) {
            val c = curParts.getOrElse(i) { 0 }
            val r = remParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}

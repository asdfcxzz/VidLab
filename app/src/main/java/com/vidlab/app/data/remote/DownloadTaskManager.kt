package com.vidlab.app.data.remote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vidlab.app.data.model.MediaFile
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class DownloadStatus {
    QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED
}

data class DownloadTaskItem(
    val id: String,
    val fileName: String,
    val remotePath: String,
    val folderName: String,
    val saveInApp: Boolean = false,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

object DownloadTaskManager {
    private const val CHANNEL_ID = "vidlab_downloads"
    private const val CHANNEL_NAME = "VidLab Downloads"

    val tasks = mutableStateListOf<DownloadTaskItem>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_CONCURRENT_DOWNLOADS = 3

    private const val PREFS_OFFLINE = "vidlab_offline_prefs"
    private const val KEY_SAVE_IN_APP = "save_downloads_in_app"
    private const val KEY_OFFLINE_ONLY = "offline_only_mode"

    fun isSaveInAppEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_OFFLINE, Context.MODE_PRIVATE).getBoolean(KEY_SAVE_IN_APP, false)

    fun setSaveInAppEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_OFFLINE, Context.MODE_PRIVATE).edit().putBoolean(KEY_SAVE_IN_APP, enabled).apply()
    }

    fun isOfflineOnlyMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_OFFLINE, Context.MODE_PRIVATE).getBoolean(KEY_OFFLINE_ONLY, false)

    fun setOfflineOnlyMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_OFFLINE, Context.MODE_PRIVATE).edit().putBoolean(KEY_OFFLINE_ONLY, enabled).apply()
    }

    fun getAppOfflineRoot(context: Context): File =
        File(context.filesDir, "offline_downloads").apply { if (!exists()) mkdirs() }

    fun getPublicOfflineRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidLab")

    fun findDownloadedFile(context: Context, logicalPath: String): File? {
        val fileName = logicalPath.substringAfterLast('/').trim()
        if (fileName.isEmpty()) return null
        val roots = listOf(getAppOfflineRoot(context), getPublicOfflineRoot())
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown().firstOrNull { it.isFile && it.name == fileName && !it.name.endsWith(".part") && it.length() > 0L }?.let {
                return it
            }
        }
        return null
    }

    fun listAllDownloadedFiles(context: Context): List<File> {
        val out = mutableListOf<File>()
        for (root in listOf(getAppOfflineRoot(context), getPublicOfflineRoot())) {
            if (root.exists()) {
                out.addAll(root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") && it.length() > 0L })
            }
        }
        return out.sortedByDescending { it.lastModified() }
    }

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows progress for downloaded episodes and movies"
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun postNotification(context: Context, task: DownloadTaskItem) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val notifId = task.id.hashCode()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.fileName)
            .setOnlyAlertOnce(true)

        when (task.status) {
            DownloadStatus.QUEUED -> {
                builder.setContentText("Queued • ${task.folderName}")
                    .setProgress(100, 0, true)
                    .setOngoing(true)
            }
            DownloadStatus.DOWNLOADING -> {
                val pct = (task.progressFraction * 100).toInt()
                val dlMb = String.format("%.1f MB", task.bytesDownloaded / (1024.0 * 1024.0))
                val totMb = if (task.totalBytes > 0L) String.format(" / %.1f MB", task.totalBytes / (1024.0 * 1024.0)) else ""
                builder.setContentText("$pct% • $dlMb$totMb")
                    .setProgress(100, pct, task.totalBytes <= 0L)
                    .setOngoing(true)
            }
            DownloadStatus.COMPLETED -> {
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentText("Saved to Downloads/VidLab/${task.folderName}")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
            }
            DownloadStatus.FAILED -> {
                builder.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentText("Failed: ${task.errorMessage ?: "Network error"}")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
            }
            DownloadStatus.CANCELLED -> {
                NotificationManagerCompat.from(context).cancel(notifId)
                return
            }
        }
        runCatching { NotificationManagerCompat.from(context).notify(notifId, builder.build()) }
    }

    private fun updateTask(id: String, transform: (DownloadTaskItem) -> DownloadTaskItem): DownloadTaskItem? {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = transform(tasks[idx])
        tasks[idx] = updated
        return updated
    }

    fun enqueueFile(context: Context, file: MediaFile, folderName: String, saveInApp: Boolean = false) {
        val appCtx = context.applicationContext
        val safeFolder = folderName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Downloads" }
        val taskId = file.path

        val existingIdx = tasks.indexOfFirst { it.id == taskId }
        if (existingIdx >= 0) {
            val st = tasks[existingIdx].status
            if (st == DownloadStatus.QUEUED || st == DownloadStatus.DOWNLOADING) return
            tasks.removeAt(existingIdx)
        }

        val initial = DownloadTaskItem(
            id = taskId,
            fileName = file.name,
            remotePath = file.path,
            folderName = safeFolder,
            saveInApp = saveInApp,
            bytesDownloaded = 0L,
            totalBytes = file.size.coerceAtLeast(0L),
            status = DownloadStatus.QUEUED
        )
        tasks.add(0, initial)
        postNotification(appCtx, initial)
        activeJobs[taskId] = scope.launch { downloadSemaphore.withPermit { if (isActive) runDownload(appCtx, initial) } }
    }

    fun enqueueBatch(context: Context, files: List<MediaFile>, folderName: String, saveInApp: Boolean = false) {
        for (f in files) enqueueFile(context, f, folderName, saveInApp)
    }

    fun cancelTask(context: Context, taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED) }?.let {
            postNotification(context.applicationContext, it)
        }
    }

    fun clearFinished() {
        tasks.removeAll {
            it.status == DownloadStatus.COMPLETED ||
            it.status == DownloadStatus.FAILED ||
            it.status == DownloadStatus.CANCELLED
        }
    }

    private suspend fun runDownload(context: Context, seed: DownloadTaskItem) {
        try {
            if (RcloneEngine.isRcloneModeEnabled(context)) {
                RcloneEngine.startForegroundServeService(context)
                val ready = RcloneEngine.ensureServerRunning(context)
                if (!ready) delay(1500L)
            }
            val streamUrl = RcloneEngine.buildStreamUrl(context, seed.remotePath)
            val baseRoot = if (seed.saveInApp) getAppOfflineRoot(context) else getPublicOfflineRoot()
            val targetDir = File(baseRoot, seed.folderName).apply { if (!exists()) mkdirs() }
            val outFile = File(targetDir, seed.fileName)
            val partFile = File(targetDir, "${seed.fileName}.part")

            var existingBytes = if (partFile.exists()) partFile.length() else 0L
            val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                requestMethod = "GET"
                if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299 && code != 206) throw IllegalStateException("HTTP $code")
            if (code == 200 && existingBytes > 0L) existingBytes = 0L

            val contentLen = conn.contentLengthLong
            val totalLen = when {
                contentLen > 0L -> existingBytes + contentLen
                seed.totalBytes > 0L -> seed.totalBytes
                else -> 0L
            }

            updateTask(seed.id) {
                it.copy(status = DownloadStatus.DOWNLOADING, bytesDownloaded = existingBytes, totalBytes = totalLen)
            }?.let { postNotification(context, it) }

            var downloaded = existingBytes
            var lastNotifyMs = 0L
            val buf = ByteArray(128 * 1024)

            conn.inputStream.use { input ->
                FileOutputStream(partFile, existingBytes > 0L).use { output ->
                    while (currentCoroutineContext().isActive) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyMs >= 1500L) {
                            lastNotifyMs = now
                            updateTask(seed.id) {
                                it.copy(status = DownloadStatus.DOWNLOADING, bytesDownloaded = downloaded, totalBytes = totalLen)
                            }?.let { postNotification(context, it) }
                        }
                    }
                    output.flush()
                }
            }
            conn.disconnect()
            if (!currentCoroutineContext().isActive) return

            if (outFile.exists()) outFile.delete()
            partFile.renameTo(outFile)

            updateTask(seed.id) {
                it.copy(status = DownloadStatus.COMPLETED, bytesDownloaded = downloaded, totalBytes = if (totalLen > 0L) totalLen else downloaded)
            }?.let { postNotification(context, it) }
        } catch (e: CancellationException) {
            updateTask(seed.id) { it.copy(status = DownloadStatus.CANCELLED) }?.let { postNotification(context, it) }
        } catch (e: Exception) {
            updateTask(seed.id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = e.message ?: "Download error")
            }?.let { postNotification(context, it) }
        } finally {
            activeJobs.remove(seed.id)
        }
    }
}

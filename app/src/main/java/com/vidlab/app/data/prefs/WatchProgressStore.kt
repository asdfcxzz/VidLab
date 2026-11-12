package com.vidlab.app.data.prefs

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

data class WatchProgress(
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAt: Long
) {
    val fraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

class WatchProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("vidlab_watch_progress", Context.MODE_PRIVATE)

    companion object {
        val revision = mutableIntStateOf(0)
        fun bump() { revision.intValue += 1 }
    }

    private fun keyPrefix(rawKey: String): String {
        val normalized = rawKey
            .removePrefix("http://127.0.0.1:8080")
            .substringBefore("?")
            .trim()
        return "prog_${normalized.hashCode()}"
    }

    fun getProgress(pathOrUrl: String): WatchProgress? {
        val p = keyPrefix(pathOrUrl)
        if (!prefs.contains("${p}_pos")) return null
        val pos = prefs.getLong("${p}_pos", 0L)
        val dur = prefs.getLong("${p}_dur", 0L)
        val watched = prefs.getBoolean("${p}_watched", false)
        val ts = prefs.getLong("${p}_ts", 0L)
        return WatchProgress(pos, dur, watched, ts)
    }

    fun saveProgress(pathOrUrl: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val p = keyPrefix(pathOrUrl)
        val pct = positionMs.toFloat() / durationMs.toFloat()
        val isWatched = pct >= 0.90f || prefs.getBoolean("${p}_watched", false)
        val storedPos = if (pct >= 0.92f) 0L else positionMs.coerceAtLeast(0L)
        prefs.edit()
            .putLong("${p}_pos", storedPos)
            .putLong("${p}_dur", durationMs)
            .putBoolean("${p}_watched", isWatched)
            .putLong("${p}_ts", System.currentTimeMillis())
            .apply()
        bump()
    }

    fun markWatched(pathOrUrl: String, watched: Boolean) {
        val p = keyPrefix(pathOrUrl)
        val dur = prefs.getLong("${p}_dur", 1000L)
        prefs.edit()
            .putLong("${p}_pos", 0L)
            .putLong("${p}_dur", dur)
            .putBoolean("${p}_watched", watched)
            .putLong("${p}_ts", System.currentTimeMillis())
            .apply()
        bump()
    }
}

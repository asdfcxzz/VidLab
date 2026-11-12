package com.vidlab.app.data.prefs

import android.content.Context

class PlayerPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("vidlab_player_prefs", Context.MODE_PRIVATE)

    var defaultSpeed: Float
        get() = prefs.getFloat("default_speed", 1.0f)
        set(value) = prefs.edit().putFloat("default_speed", value).apply()

    var seekStepSeconds: Int
        get() = prefs.getInt("seek_step_seconds", 10)
        set(value) = prefs.edit().putInt("seek_step_seconds", value).apply()

    var preferFfmpegAudio: Boolean
        get() = prefs.getBoolean("prefer_ffmpeg_audio", false)
        set(value) = prefs.edit().putBoolean("prefer_ffmpeg_audio", value).apply()

    var autoEnglishTracks: Boolean
        get() = prefs.getBoolean("auto_english_tracks", true)
        set(value) = prefs.edit().putBoolean("auto_english_tracks", value).apply()

    var defaultResizeMode: Int
        get() = prefs.getInt("default_resize_mode", 0)
        set(value) = prefs.edit().putInt("default_resize_mode", value).apply()

    var subtitleFontSizeSp: Float
        get() = prefs.getFloat("subtitle_font_size_sp", 18f)
        set(value) = prefs.edit().putFloat("subtitle_font_size_sp", value).apply()

    var subtitleColorIndex: Int
        get() = prefs.getInt("subtitle_color_index", 0)
        set(value) = prefs.edit().putInt("subtitle_color_index", value).apply()

    var subtitleBgBox: Boolean
        get() = prefs.getBoolean("subtitle_bg_box", false)
        set(value) = prefs.edit().putBoolean("subtitle_bg_box", value).apply()

    var subtitleBottomPaddingPct: Int
        get() = prefs.getInt("subtitle_bottom_padding_pct", 4)
        set(value) = prefs.edit().putInt("subtitle_bottom_padding_pct", value).apply()

    var volumeGestureEnabled: Boolean
        get() = prefs.getBoolean("volume_gesture_enabled", true)
        set(value) = prefs.edit().putBoolean("volume_gesture_enabled", value).apply()

    var brightnessGestureEnabled: Boolean
        get() = prefs.getBoolean("brightness_gesture_enabled", false)
        set(value) = prefs.edit().putBoolean("brightness_gesture_enabled", value).apply()

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}

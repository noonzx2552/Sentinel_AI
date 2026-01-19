package com.sentinel.ai.utils

import android.content.Context

object ProtectionPrefs {
    private const val PREFS_NAME = "protection_prefs"
    private const val KEY_ENABLED = "protection_enabled"
    private const val KEY_MODE = "protection_mode"
    private const val KEY_USE_CALL_PLAYBACK_CAPTURE = "use_call_playback_capture"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun getMode(context: Context): ProtectionMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, ProtectionMode.AUTO.name)
        return runCatching { ProtectionMode.valueOf(raw ?: ProtectionMode.AUTO.name) }
            .getOrDefault(ProtectionMode.AUTO)
    }

    fun setMode(context: Context, mode: ProtectionMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    fun useCallPlaybackCapture(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_CALL_PLAYBACK_CAPTURE, false)
    }

    fun setUseCallPlaybackCapture(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_CALL_PLAYBACK_CAPTURE, enabled)
            .apply()
    }
}

enum class ProtectionMode {
    MONITORING,
    WARNING,
    AUTO
}

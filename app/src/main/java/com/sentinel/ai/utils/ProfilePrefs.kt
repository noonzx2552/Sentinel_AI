package com.sentinel.ai.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ProfilePrefs {
    private const val PREFS_NAME = "profile_prefs"
    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_DARK_MODE = "dark_mode"

    fun getName(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILE_NAME, "Alex Morgan") ?: "Alex Morgan"
    }

    fun setName(context: Context, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILE_NAME, name)
            .apply()
    }

    fun isDarkMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, false)
    }

    fun ensureDefaultTheme(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DARK_MODE)) {
            prefs.edit().putBoolean(KEY_DARK_MODE, false).apply()
            applyTheme(false)
        }
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, enabled)
            .apply()
        
        applyTheme(enabled)
    }

    fun applyTheme(enabled: Boolean) {
        val mode = if (enabled) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}

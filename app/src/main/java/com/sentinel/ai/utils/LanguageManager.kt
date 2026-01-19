package com.sentinel.ai.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LanguageManager {
    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANGUAGE = "app_language"
    const val ACTION_LANGUAGE_CHANGED = "com.sentinel.ai.ACTION_LANGUAGE_CHANGED"

    const val LANG_EN = "en"
    const val LANG_TH = "th"

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_LANGUAGE, null)
        if (stored.isNullOrBlank()) {
            prefs.edit().putString(KEY_LANGUAGE, LANG_EN).apply()
            applyAppLocale(LANG_EN)
            return LANG_EN
        }
        return stored
    }

    fun setLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language)
            .apply()
        applyAppLocale(language)
        notifyLanguageChanged(context.applicationContext)
    }

    fun wrap(context: Context): Context {
        val language = getLanguage(context)
        applyAppLocale(language)
        return updateResources(context, language)
    }

    fun wrapWithLanguage(context: Context, language: String): Context {
        applyAppLocale(language)
        return updateResources(context, language)
    }

    fun contextForLanguage(context: Context, language: String): Context {
        return createLocalizedContext(context, language)
    }

    fun notifyLanguageChanged(context: Context) {
        context.sendBroadcast(android.content.Intent(ACTION_LANGUAGE_CHANGED))
    }

    fun ensureDefaultLanguage(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_LANGUAGE, null)
        if (stored.isNullOrBlank()) {
            prefs.edit().putString(KEY_LANGUAGE, LANG_EN).apply()
            applyAppLocale(LANG_EN)
        }
    }

    private fun updateResources(context: Context, language: String): Context {
        val locale = Locale(language.ifBlank { LANG_EN })
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    private fun createLocalizedContext(context: Context, language: String): Context {
        val locale = Locale(language.ifBlank { LANG_EN })
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    private fun applyAppLocale(language: String) {
        val locales = LocaleListCompat.forLanguageTags(language.ifBlank { LANG_EN })
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

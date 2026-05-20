package com.sentinel.ai.utils

import android.content.Context
import com.sentinel.ai.R

object EventLocalization {
    data class LocalizedEvent(val source: String, val content: String)

    private val sourceResIds = listOf(
        R.string.calllog_event_source,
        R.string.scan_event_source_file,
        R.string.scan_event_source_web,
        R.string.scan_event_source_number,
        R.string.scan_event_source_qr
    )

    fun localizeEvent(context: Context, source: String, content: String): LocalizedEvent {
        val localizedSource = localizeSource(context, source)
        val localizedContent = localizeContent(context, content)
        return LocalizedEvent(localizedSource, localizedContent)
    }

    fun isScanSource(context: Context, source: String): Boolean {
        val normalized = source.trim()
        if (normalized.isBlank()) return false
        val candidates = allSourceCandidates(context)
        return candidates.any { it.equals(normalized, ignoreCase = true) } ||
            normalized.contains("scan", ignoreCase = true) ||
            normalized.contains("qr", ignoreCase = true)
    }

    fun matchesSourceRes(context: Context, source: String, resId: Int): Boolean {
        val normalized = source.trim()
        if (normalized.isBlank()) return false
        val langs = listOf(LanguageManager.LANG_EN, LanguageManager.LANG_TH)
        return langs.any { lang ->
            getStringForLanguage(context, lang, resId).equals(normalized, ignoreCase = true)
        }
    }

    private fun localizeSource(context: Context, source: String): String {
        val normalized = source.trim()
        if (normalized.isBlank()) return source
        val candidates = allSourceCandidates(context)
        val resId = candidates.firstOrNull { it.equals(normalized, ignoreCase = true) }
            ?.let { lookupSourceResId(context, it) }
        return if (resId != null) context.getString(resId) else source
    }

    private fun allSourceCandidates(context: Context): List<String> {
        val langs = listOf(LanguageManager.LANG_EN, LanguageManager.LANG_TH)
        val results = mutableListOf<String>()
        sourceResIds.forEach { resId ->
            langs.forEach { lang ->
                results.add(getStringForLanguage(context, lang, resId))
            }
        }
        return results
    }

    private fun lookupSourceResId(context: Context, candidate: String): Int? {
        val langs = listOf(LanguageManager.LANG_EN, LanguageManager.LANG_TH)
        sourceResIds.forEach { resId ->
            langs.forEach { lang ->
                val value = getStringForLanguage(context, lang, resId)
                if (value.equals(candidate, ignoreCase = true)) return resId
            }
        }
        return null
    }

    private fun localizeContent(context: Context, content: String): String {
        val normalized = content.trim()
        if (normalized.isBlank()) return content

        extractFormattedValue(context, R.string.scan_event_content_url_format, normalized)?.let { url ->
            return context.getString(R.string.scan_event_content_url_format, url)
        }
        extractFormattedValue(context, R.string.scan_event_content_phone_format, normalized)?.let { phone ->
            return context.getString(R.string.scan_event_content_phone_format, phone)
        }
        extractNumericValue(context, R.string.calllog_event_content_format, normalized)?.let { count ->
            val countInt = count.toIntOrNull()
            return if (countInt != null) {
                context.getString(R.string.calllog_event_content_format, countInt)
            } else {
                content
            }
        }
        extractFileParts(normalized)?.let { parts ->
            return context.getString(
                R.string.scan_event_content_file_format,
                parts[0],
                parts[1],
                parts[2],
                parts[3]
            )
        }
        return content
    }

    private fun extractFormattedValue(context: Context, resId: Int, text: String): String? {
        val token = "__VALUE__"
        val langs = listOf(LanguageManager.LANG_EN, LanguageManager.LANG_TH)
        langs.forEach { lang ->
            val pattern = getStringForLanguage(context, lang, resId, token)
            val prefix = pattern.substringBefore(token)
            val suffix = pattern.substringAfter(token)
            if (text.startsWith(prefix) && text.endsWith(suffix)) {
                val value = text.substring(prefix.length, text.length - suffix.length).trim()
                if (value.isNotBlank()) return value
            }
        }
        return null
    }

    private fun extractNumericValue(context: Context, resId: Int, text: String): String? {
        val langs = listOf(LanguageManager.LANG_EN, LanguageManager.LANG_TH)
        langs.forEach { lang ->
            val localized = LanguageManager.contextForLanguage(context, lang)
            val pattern = localized.getString(resId)
            val placeholder = pattern
                .replace("%1\$d", "__NUM__")
                .replace("%d", "__NUM__")
            val regex = Regex(Regex.escape(placeholder).replace("__NUM__", "(\\\\d+)"))
            val match = regex.find(text) ?: return@forEach
            val value = match.groupValues.getOrNull(1).orEmpty()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun extractFileParts(text: String): List<String>? {
        val parts = text.split(" | ")
        return if (parts.size == 4) parts else null
    }

    private fun getStringForLanguage(context: Context, language: String, resId: Int, vararg args: Any): String {
        val localized = LanguageManager.contextForLanguage(context, language)
        return localized.getString(resId, *args)
    }
}

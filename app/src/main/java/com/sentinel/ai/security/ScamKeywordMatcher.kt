package com.sentinel.ai.security

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Matches transcript text against scam scenario keywords from scammerkeyword.json.
 * Uses canonical, variants, and sample sentences (TH/EN/Karaoke) to detect risk phrases.
 */
data class ScamKeywordMatch(val scenarioId: String, val scenarioName: String, val matchedKeyword: String)

class ScamKeywordMatcher(private val context: Context) {

    private data class Entry(val keyword: String, val scenarioId: String, val scenarioName: String)

    private val entries: List<Entry> by lazy { load() }

    private fun load(): List<Entry> {
        val list = mutableListOf<Entry>()
        try {
            context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
                val json = JSONObject(reader.readText())
                val scenarios = json.optJSONArray("scenarios") ?: return list
                for (i in 0 until scenarios.length()) {
                    val s = scenarios.optJSONObject(i) ?: continue
                    val id = s.optString("id", "")
                    val name = s.optString("name", "")
                    if (id.isBlank()) continue
                    val words = s.optJSONArray("words")
                    if (words != null) {
                        for (j in 0 until words.length()) {
                            val w = words.optJSONObject(j) ?: continue
                            addKeyword(list, w.optString("canonical", ""), id, name)
                            val variants = w.optJSONArray("variants") ?: continue
                            for (k in 0 until variants.length()) {
                                addKeyword(list, variants.optString(k, ""), id, name)
                            }
                        }
                    }

                    val sentences = s.optJSONArray("sentences")
                    if (sentences != null) {
                        for (j in 0 until sentences.length()) {
                            val sentence = sentences.optJSONObject(j) ?: continue
                            addKeyword(list, sentence.optString("th", ""), id, name)
                            addKeyword(list, sentence.optString("en", ""), id, name)
                            addKeyword(list, sentence.optString("karaoke", ""), id, name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load $ASSET_NAME failed", e)
        }
        return list.distinctBy { it.keyword.lowercase() to it.scenarioId }
    }

    private fun addKeyword(list: MutableList<Entry>, keyword: String, scenarioId: String, scenarioName: String) {
        val value = keyword.trim()
        if (value.length >= MIN_KEYWORD_LENGTH) {
            list.add(Entry(value, scenarioId, scenarioName))
        }
    }

    /**
     * Returns the first scenario whose keyword (canonical or variant) appears in [transcript].
     * Case-insensitive for ASCII; Thai/complex scripts matched as-is.
     */
    fun match(transcript: String): ScamKeywordMatch? {
        val t = transcript.trim()
        if (t.isBlank()) return null
        for (e in entries) {
            if (t.contains(e.keyword, ignoreCase = true)) return ScamKeywordMatch(e.scenarioId, e.scenarioName, e.keyword)
        }
        return null
    }

    companion object {
        private const val TAG = "ScamKeywordMatcher"
        private const val ASSET_NAME = "scammerkeyword.json"
        private const val MIN_KEYWORD_LENGTH = 2

        @Volatile
        private var instance: ScamKeywordMatcher? = null

        fun get(context: Context): ScamKeywordMatcher = instance ?: synchronized(this) {
            instance ?: ScamKeywordMatcher(context.applicationContext).also { instance = it }
        }
    }
}

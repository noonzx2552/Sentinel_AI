package com.sentinel.ai.security

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Matches transcript text against scam scenario keywords from scammerkeyword.json.
 * Uses canonical + variants (รวมคำเพี้ยน/สะกดหลอก จาก STT) to detect risk phrases.
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
                    val words = s.optJSONArray("words") ?: continue
                    for (j in 0 until words.length()) {
                        val w = words.optJSONObject(j) ?: continue
                        val canon = w.optString("canonical", "").trim()
                        val vars = w.optJSONArray("variants")
                        val all = mutableListOf<String>()
                        if (canon.isNotBlank()) all.add(canon)
                        if (vars != null) for (k in 0 until vars.length()) {
                            val v = vars.optString(k, "").trim()
                            if (v.isNotBlank()) all.add(v)
                        }
                        for (kw in all.distinct()) if (kw.length >= 2) list.add(Entry(kw, id, name))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load $ASSET_NAME failed", e)
        }
        return list
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

        @Volatile
        private var instance: ScamKeywordMatcher? = null

        fun get(context: Context): ScamKeywordMatcher = instance ?: synchronized(this) {
            instance ?: ScamKeywordMatcher(context.applicationContext).also { instance = it }
        }
    }
}

package com.sentinel.ai.ai

import android.util.Base64
import android.util.Log
import com.sentinel.ai.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over Google Speech-to-Text REST API (sync recognize).
 * Sends raw PCM 16-bit LE, 16kHz audio bytes and returns the first transcript.
 */
object CloudSttClient {
    private const val TAG = "CloudSttClient"
    private const val ENDPOINT = "https://speech.googleapis.com/v1p1beta1/speech:recognize"
    private const val LANGUAGE = "th-TH"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun transcribePcm16(audioBytes: ByteArray, sampleRate: Int = 16000): String? {
        val apiKey = BuildConfig.STT_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "STT API key missing. Set STT_API_KEY in gradle.properties/local.properties.")
            return null
        }
        val payload = buildPayload(audioBytes, sampleRate)
        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .post(payload.toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "STT HTTP ${resp.code}: ${resp.body?.string()}")
                    return null
                }
                val body = resp.body?.string().orEmpty()
                parseTranscript(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "STT request failed: ${e.message}", e)
            null
        }
    }

    private fun buildPayload(audioBytes: ByteArray, sampleRate: Int): String {
        val json = JSONObject()
        val config = JSONObject()
            .put("encoding", "LINEAR16")
            .put("languageCode", LANGUAGE)
            .put("sampleRateHertz", sampleRate)
            .put("enableAutomaticPunctuation", true)
        val audio = JSONObject()
            .put("content", Base64.encodeToString(audioBytes, Base64.NO_WRAP))
        json.put("config", config)
        json.put("audio", audio)
        return json.toString()
    }

    private fun parseTranscript(body: String): String? {
        return try {
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val firstAlt: JSONArray? = results.optJSONObject(0)
                ?.optJSONArray("alternatives")
            firstAlt?.optJSONObject(0)?.optString("transcript")
        } catch (e: Exception) {
            Log.w(TAG, "STT parse error: ${e.message}")
            null
        }
    }
}

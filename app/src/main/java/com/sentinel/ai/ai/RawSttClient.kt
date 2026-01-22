package com.sentinel.ai.ai

import android.util.Base64
import android.util.Log
import com.sentinel.ai.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for raw STT endpoint that accepts base64 audio.
 *
 * API:
 * POST https://voice.smarthomeus3r.space/stt/raw
 * Headers: X-API-Key: <key>
 * Body (application/json):
 * {
 *   "audio_base64": "<base64>",
 *   "filename": "chunk.wav",
 *   "model": "medium",
 *   "language": "th",
 *   "task": "transcribe"
 * }
 */
object RawSttClient {
    private const val TAG = "RawSttClient"
    private const val ENDPOINT = "https://voice.smarthomeus3r.space/stt/raw"
    private val JSON = "application/json".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun transcribeBase64(base64: String, fileName: String = "chunk.wav"): String? {
        if (base64.isBlank()) return null
        if (!WhisperCppSttClient.isConfigured()) {
            Log.w(TAG, "Missing STT API key; set WHISPER_CPP_API_KEY.")
            return null
        }
        val payload = JSONObject().apply {
            put("audio_base64", base64)
            put("filename", fileName)
            put("model", "medium")
            put("language", "th")
            put("task", "transcribe")
        }
        val body = payload.toString().toRequestBody(JSON)
        val req = Request.Builder()
            .url(ENDPOINT)
            .addHeader("X-API-Key", BuildConfig.WHISPER_CPP_API_KEY)
            .post(body)
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Raw STT HTTP ${resp.code}: ${resp.message}")
                    return null
                }
                val raw = resp.body?.string().orEmpty()
                parseText(raw)
            }
        }.onFailure { e ->
            Log.w(TAG, "Raw STT failed: ${e.message}", e)
        }.getOrNull()
    }

    fun transcribePcm16(pcm: ByteArray): String? {
        if (pcm.isEmpty()) return null
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        return transcribeBase64(b64)
    }

    private fun parseText(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            JSONObject(raw).optString("text").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Raw STT parse error: ${e.message} body=$raw")
            null
        }
    }
}

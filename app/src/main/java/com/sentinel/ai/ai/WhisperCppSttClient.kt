package com.sentinel.ai.ai

import android.util.Log
import com.sentinel.ai.BuildConfig
import com.sentinel.ai.utils.WavUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the self-hosted Whisper.cpp endpoint.
 *
 * API contract:
 *   POST https://voice.smarthomeus3r.space/stt
 *   Headers: X-API-Key: <key>
 *   Body: multipart/form-data with "file" part (audio/wav, m4a, etc.)
 *   Response: { "text": "...", "lang": "...", "time_ms": 1234.5 }
 */
object WhisperCppSttClient {
    private const val TAG = "WhisperCppSttClient"
    private const val ENDPOINT = "https://voice.smarthomeus3r.space/stt"
    private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1)) // avoid occasional HTTP/2 stalls
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun isConfigured(): Boolean = BuildConfig.WHISPER_CPP_API_KEY.isNotBlank()

    /**
     * Transcribe a PCM 16-bit little-endian buffer. Converts to WAV before sending.
     */
    fun transcribePcm16(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int
    ): String? {
        if (pcm.isEmpty()) return null
        if (!isConfigured()) {
            Log.w(TAG, "Missing Whisper.cpp API key; set WHISPER_CPP_API_KEY.")
            return null
        }
        val wavBytes = try {
            WavUtil.pcmToWav(pcm, sampleRate, channels, 16)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to wrap PCM to WAV: ${e.message}", e)
            return null
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "chunk.wav",
                wavBytes.toRequestBody(WAV_MEDIA_TYPE)
            )
            .build()

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("X-API-Key", BuildConfig.WHISPER_CPP_API_KEY)
            .post(body)
            .build()

        return try {
            Log.d(TAG, "Sending wav bytes=${wavBytes.size} sr=$sampleRate ch=$channels")
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Whisper.cpp HTTP ${resp.code}: ${resp.message}")
                    return null
                }
                val raw = resp.body?.string().orEmpty()
                parseText(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Whisper.cpp request failed: ${e.message}", e)
            null
        }
    }

    /**
     * Transcribe an audio file (wav/m4a/mp3/ogg). Uses the server to decode.
     */
    fun transcribeFile(file: File, mimeType: String?): String? {
        if (!file.exists() || file.length() == 0L) return null
        if (!isConfigured()) {
            Log.w(TAG, "Missing Whisper.cpp API key; set WHISPER_CPP_API_KEY.")
            return null
        }
        val mediaType = (mimeType?.ifBlank { null } ?: "application/octet-stream").toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody(mediaType)
            )
            .build()

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("X-API-Key", BuildConfig.WHISPER_CPP_API_KEY)
            .post(body)
            .build()

        return try {
            Log.d(TAG, "Sending file ${file.name} bytes=${file.length()} mime=${mediaType}")
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Whisper.cpp HTTP ${resp.code}: ${resp.message}")
                    return null
                }
                val raw = resp.body?.string().orEmpty()
                parseText(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Whisper.cpp request failed: ${e.message}", e)
            null
        }
    }

    private fun parseText(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val obj = JSONObject(raw)
            obj.optString("text").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Whisper.cpp parse error: ${e.message} body=$raw")
            null
        }
    }
}

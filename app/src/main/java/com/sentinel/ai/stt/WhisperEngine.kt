package com.sentinel.ai.stt

import android.util.Log
import com.sentinel.ai.BuildConfig
import com.sentinel.ai.utils.WavUtil
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * A client for interacting with the OpenAI Whisper API for speech-to-text transcription.
 */
object WhisperEngine {

    private const val TAG = "WhisperEngine"
    private const val WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions"
    private val client = OkHttpClient()

    // Audio format parameters required by the capture service and WAV utility
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2 // Stereo
    const val BIT_DEPTH = 16 // PCM 16-bit

    /**
     * Transcribes the given raw PCM audio data using the Whisper API.
     *
     * @param pcmData The raw PCM 16-bit audio data.
     * @return The transcribed text, or null if an error occurred.
     */
    fun transcribe(pcmData: ByteArray): String? {
        if (BuildConfig.OPENAI_API_KEY.isEmpty()) {
            Log.e(TAG, "OpenAI API key is not set. Please provide it in your gradle.properties.")
            return null
        }

        try {
            // Wrap the raw PCM data in a WAV container
            val wavData = WavUtil.pcmToWav(pcmData, SAMPLE_RATE, CHANNELS, BIT_DEPTH)

            // Create the request body
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    wavData.toRequestBody("audio/wav".toMediaTypeOrNull())
                )
                .addFormDataPart("model", "whisper-1")
                .build()

            // Build the HTTP request
            val request = Request.Builder()
                .url(WHISPER_API_URL)
                .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
                .post(requestBody)
                .build()

            // Execute the request and process the response
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Whisper API request failed: ${response.code} ${response.message}")
                    Log.e(TAG, "Response body: ${response.body?.string()}")
                    return null
                }

                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val jsonObject = JSONObject(responseBody)
                    val transcript = jsonObject.optString("text", "")
                    Log.d(TAG, "Transcription successful: '$transcript'")
                    return transcript
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException during transcription: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "An unexpected error occurred during transcription: ${e.message}", e)
        }
        return null
    }
}

package com.sentinel.ai.service

import com.sentinel.ai.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Buffers PCM audio, wraps it as WAV, and sends it to Whisper.
 */
class PressureSpeechEngine(
    private val listener: PressureTranscriptListener
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer = ByteArrayOutputStream()
    private val mutex = Mutex()
    private val client = OkHttpClient()

    @Volatile
    private var uploading = false

    fun submitPcmChunk(chunk: ByteArray) {
        scope.launch {
            mutex.withLock { buffer.write(chunk) }
            scheduleUploadIfNeeded()
        }
    }

    fun stop() {
        scope.launch {
            mutex.withLock {
                buffer.reset()
                uploading = false
            }
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun scheduleUploadIfNeeded() {
        if (BuildConfig.OPENAI_API_KEY.isBlank()) {
            mutex.withLock { buffer.reset() }
            return
        }
        if (uploading) return
        val payload = mutex.withLock {
            if (buffer.size() >= TARGET_BYTES) {
                uploading = true
                val bytes = buffer.toByteArray()
                buffer.reset()
                bytes
            } else {
                null
            }
        } ?: return
        transcribe(payload)
    }

    private suspend fun transcribe(pcmData: ByteArray) {
        try {
            if (pcmData.isEmpty()) return
            val wavBytes = buildWav(pcmData)
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart(
                    "file",
                    "pressure.wav",
                    wavBytes.toRequestBody(WAV_MEDIA_TYPE)
                )
                .build()

            val request = Request.Builder()
                .url(WHISPER_URL)
                .addHeader("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return
                val bodyString = resp.body?.string() ?: return
                parseTranscript(bodyString)?.let { listener.onTranscript(it) }
            }
        } catch (_: Exception) {
            // Ignore network/parse errors to keep the loop running.
        } finally {
            mutex.withLock { uploading = false }
            scope.launch { scheduleUploadIfNeeded() }
        }
    }

    private fun parseTranscript(raw: String): String? {
        return try {
            JSONObject(raw).optString("text").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildWav(pcm: ByteArray): ByteArray {
        val totalDataLen = pcm.size + 36
        val totalAudioLen = pcm.size
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(CHANNEL_COUNT.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort((CHANNEL_COUNT * BYTES_PER_SAMPLE).toShort())
            putShort((BYTES_PER_SAMPLE * 8).toShort())
            put("data".toByteArray())
            putInt(totalAudioLen)
        }.array()
        return header + pcm
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_DURATION_MS = 4000
        private const val WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions"
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
        private val TARGET_BYTES = (SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE * CHUNK_DURATION_MS) / 1000
    }
}

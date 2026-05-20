package com.sentinel.ai.ai

import android.content.Context
import com.sentinel.ai.BuildConfig
import com.sentinel.ai.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

class WhisperCppSttEngine : SttEngine {
    override val engineName: String = "Whisper.cpp"

    override suspend fun transcribe(pcm16Mono16k: ByteArray): SttResult = withContext(Dispatchers.IO) {
        var text: String? = null
        var error: String? = null
        val latency = measureTimeMillis {
            runCatching { RawSttClient.transcribePcm16(pcm16Mono16k) }
                .onSuccess { text = it }
                .onFailure { error = it.message }
        }
        SttResult(text = text.orEmpty(), confidence = null, engineName = engineName, latencyMs = latency, error = error)
    }
}

class GoogleCloudSttEngine : SttEngine {
    override val engineName: String = "Google Cloud STT"

    override suspend fun transcribe(pcm16Mono16k: ByteArray): SttResult = withContext(Dispatchers.IO) {
        var text: String? = null
        var error: String? = null
        val latency = measureTimeMillis {
            runCatching { CloudSttClient.transcribePcm16(pcm16Mono16k, 16000) }
                .onSuccess { text = it }
                .onFailure { error = it.message }
        }
        SttResult(text = text.orEmpty(), confidence = null, engineName = engineName, latencyMs = latency, error = error)
    }
}

class VoskOfflineSttEngine(private val context: Context) : SttEngine {
    override val engineName: String = "Vosk Offline"

    override suspend fun transcribe(pcm16Mono16k: ByteArray): SttResult = withContext(Dispatchers.IO) {
        var text: String? = null
        var error: String? = null
        val latency = measureTimeMillis {
            runCatching { OfflineStt.transcribePcm16(context, pcm16Mono16k, 16000, isStereo = false) }
                .onSuccess { text = it }
                .onFailure { error = it.message }
        }
        SttResult(text = text.orEmpty(), confidence = null, engineName = engineName, latencyMs = latency, error = error)
    }
}

class AndroidSpeechRecognizerEngine : SttEngine {
    override val engineName: String = "Android SpeechRecognizer"

    override suspend fun transcribe(pcm16Mono16k: ByteArray): SttResult {
        return SttResult(
            text = "",
            confidence = null,
            engineName = engineName,
            latencyMs = 0L,
            error = "Streaming SpeechRecognizer fallback is handled by CallProtectionOrchestrator"
        )
    }
}

class SttEngineSelector(private val context: Context) {
    fun orderedEngines(): List<SttEngine> {
        val online = runCatching { NetworkUtils.isOnline(context) }.getOrDefault(false)
        val engines = mutableListOf<SttEngine>()
        if (online && WhisperCppSttClient.isConfigured()) engines += WhisperCppSttEngine()
        if (online && BuildConfig.STT_API_KEY.isNotBlank()) engines += GoogleCloudSttEngine()
        engines += VoskOfflineSttEngine(context.applicationContext)
        engines += AndroidSpeechRecognizerEngine()
        return engines
    }

    suspend fun transcribeWithFallback(pcm16Mono16k: ByteArray): SttResult {
        for (engine in orderedEngines()) {
            val result = engine.transcribe(pcm16Mono16k)
            if (result.text.isNotBlank()) return result
            if (engine is AndroidSpeechRecognizerEngine) return result
        }
        return SttResult("", null, "none", 0L, "No STT engine available")
    }
}

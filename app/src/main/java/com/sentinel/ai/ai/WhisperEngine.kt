package com.sentinel.ai.ai

import android.content.Context

/**
 * STT bridge. Prefers offline Vosk; if unavailable, falls back to Google STT when keyed.
 */
class WhisperEngine(private val appContext: Context) {
    /**
     * Transcribes PCM 16-bit LE audio. If no audio provided, returns empty.
     */
    fun transcribe(audioData: ByteArray? = null): String {
        if (audioData == null) return ""
        // Prefer offline Vosk
        OfflineStt.transcribePcm16(appContext, audioData, sampleRate = 16000, isStereo = true)?.let {
            if (it.isNotBlank()) return it
        }
        // Cloud fallback if key provided
        return CloudSttClient.transcribePcm16(audioData, sampleRate = 16000) ?: ""
    }
}

package com.sentinel.ai.ai

/**
 * Placeholder for Whisper STT. Currently returns empty to avoid injecting fake text.
 * Real implementation should transcribe audioData on-device.
 */
class WhisperEngine {
    fun transcribe(audioData: ByteArray? = null): String = ""
}

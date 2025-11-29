package com.sentinel.ai.ai

/**
 * Placeholder for Whisper STT. The real implementation would stream audio into
 * an on-device quantized model. Audio buffers should be kept in RAM only.
 */
class WhisperEngine {
    fun transcribe(audioData: ByteArray? = null): String {
        // Simulated transcript for demo purposes.
        return "ครับ คุณต้องโอนเงินภายใน 10 นาทีเพื่อหลีกเลี่ยงหมายศาล"
    }
}

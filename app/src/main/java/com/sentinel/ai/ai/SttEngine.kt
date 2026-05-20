package com.sentinel.ai.ai

interface SttEngine {
    val engineName: String
    suspend fun transcribe(pcm16Mono16k: ByteArray): SttResult
}

data class SttResult(
    val text: String,
    val confidence: Float?,
    val engineName: String,
    val latencyMs: Long,
    val error: String? = null
)

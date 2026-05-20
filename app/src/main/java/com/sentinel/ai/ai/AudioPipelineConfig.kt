package com.sentinel.ai.ai

import android.media.AudioFormat

data class AudioPipelineConfig(
    val sampleRate: Int = 16000,
    val channelMask: Int = AudioFormat.CHANNEL_IN_MONO,
    val encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    val chunkDurationMs: Int = 2000,
    val overlapMs: Int = 500,
    val vadSkipThreshold: Float = 0.50f,
    val vadSendThreshold: Float = 0.70f
) {
    val bytesPerSample: Int = 2
    val chunkBytes: Int = sampleRate * bytesPerSample * chunkDurationMs / 1000
    val stepBytes: Int = sampleRate * bytesPerSample * (chunkDurationMs - overlapMs) / 1000
}

package com.sentinel.ai.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/**
     * Lightweight microphone capturer that streams raw PCM chunks for on-device processing.
     * (Currently unused for UI; kept for future offline Whisper integration.)
     */
class MicCaptureManager(private val context: Context) {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private var audioRecord: AudioRecord? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    @Volatile private var running = false

    fun start(
        onChunk: (ByteArray) -> Unit,
        onError: (String) -> Unit,
        chunkMs: Int = 1500
    ) {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Microphone permission missing")
            return
        }
        val bufferSize = (sampleRate * (chunkMs / 1000f) * 2).toInt().coerceAtLeast(minBuffer)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            onError("Cannot init mic")
            cleanup()
            return
        }
        workerThread = HandlerThread("sentinel-mic-capture").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)
        running = true
        audioRecord?.startRecording()
        workerHandler?.post {
            val chunkBuffer = ByteArray(bufferSize)
            val baos = ByteArrayOutputStream()
            while (running) {
                val read = audioRecord?.read(chunkBuffer, 0, chunkBuffer.size) ?: break
                if (read <= 0) {
                    onError("Mic read error: $read")
                    break
                }
                baos.write(chunkBuffer, 0, read)
                // Emit roughly every chunkMs
                if (baos.size() >= bufferSize) {
                    onChunk(baos.toByteArray())
                    baos.reset()
                }
            }
            baos.reset()
        }
    }

    fun stop() {
        running = false
        audioRecord?.stop()
        cleanup()
    }

    private fun cleanup() {
        audioRecord?.release()
        audioRecord = null
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerHandler = null
        workerThread = null
    }
}

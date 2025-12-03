package com.sentinel.ai.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi

/**
 * Captures internal audio (media/voice) via MediaProjection (Android 10+)
 * and streams raw PCM chunks to a callback. Buffers are kept in memory only.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackCaptureController(private val mediaProjection: MediaProjection) {

    private val sampleRate = 16000
    private val channelMask = AudioFormat.CHANNEL_IN_STEREO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private val audioFormat = AudioFormat.Builder()
        .setSampleRate(sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private val config = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
        .addMatchingUsage(AudioAttributes.USAGE_GAME)
        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .build()

    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding).coerceAtLeast(4096)
    private var audioRecord: AudioRecord? = null
    private var workerThread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var running = false

    fun start(onChunk: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (running) return
        try {
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize * 2)
                .build()
        } catch (e: Exception) {
            onError("Playback capture failed: ${e.message ?: "init error"}")
            cleanup()
            return
        }
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            onError("Cannot init playback capture")
            cleanup()
            return
        }
        workerThread = HandlerThread("sentinel-playback-capture").also { it.start() }
        handler = Handler(workerThread!!.looper)
        running = true
        try {
            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                onError("Playback capture failed: not recording")
                stop()
                return
            }
        } catch (e: Exception) {
            onError("Playback capture failed: ${e.message ?: "could not start"}")
            stop()
            return
        }
        handler?.post {
            val buf = ByteArray(bufferSize)
            while (running) {
                try {
                    val read = audioRecord?.read(buf, 0, buf.size) ?: break
                    if (read <= 0) {
                        onError("Playback read error: $read")
                        break
                    }
                    onChunk(buf.copyOf(read))
                } catch (e: Exception) {
                    onError("Playback read exception: ${e.message ?: "unknown"}")
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        audioRecord?.stop()
        cleanup()
    }

    fun destroy() {
        stop()
    }

    private fun cleanup() {
        audioRecord?.release()
        audioRecord = null
        handler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        handler = null
        workerThread = null
    }
}

package com.sentinel.ai.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sentinel.ai.R
import com.sentinel.ai.ai.SileroVad
import com.sentinel.ai.ai.WhisperCppSttClient
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.utils.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * During a call: captures playback audio (including USAGE_VOICE_COMMUNICATION when allowed)
 * via MediaProjection, buffers ~2s chunks, and sends to WhisperCppSttClient — same path as
 * Dashboard debug mode. Updates overlay and GuardianEventStore with transcripts.
 */
class CallPlaybackCaptureService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var overlay: OverlayController

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile private var isStopping = false
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var vad: SileroVad? = null

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        overlay = OverlayController(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val notif = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        return when (intent.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startCapture(resultCode, data)
                } else {
                    Log.w(TAG, "Missing projection data")
                    broadcastFallback()
                    stopSelf()
                }
                START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopCapture()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startCapture(resultCode: Int, projectionData: Intent) {
        if (captureJob?.isActive == true) return
        if (!WhisperCppSttClient.isConfigured()) {
            Log.w(TAG, "Whisper.cpp not configured")
            broadcastFallback()
            stopSelf()
            return
        }

        vad = try { SileroVad(this) } catch (e: Exception) {
            Log.w(TAG, "Silero VAD init failed: ${e.message}", e)
            null
        }
        vad?.reset()

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, projectionData).also { mp ->
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system")
                    stopCapture()
                }
            }, null)
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioRecord = try {
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord build failed: ${e.message}", e)
            broadcastFallback()
            stopSelf()
            return
        }

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start failed: ${e.message}", e)
            broadcastFallback()
            stopSelf()
            return
        }

        isServiceRunning = true
        overlay.showLiveTranscript(getString(R.string.overlay_listening))

        // Accumulate ~2 sec (16kHz * 2 bytes * 2 sec) then send to Whisper (same as debug path)
        val chunkBytes = SAMPLE_RATE * 2 * CHUNK_SEC
        val accumulate = ArrayList<ByteArray>()
        var total = 0

        captureJob = serviceScope.launch {
            val buf = ByteArray(bufferSize)
            while (isActive) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read <= 0) {
                    if (read < 0) Log.w(TAG, "AudioRecord read=$read")
                    continue
                }
                accumulate.add(buf.copyOf(read))
                total += read
                if (total < chunkBytes) continue

                val chunk = ByteArray(total)
                var off = 0
                for (a in accumulate) {
                    System.arraycopy(a, 0, chunk, off, a.size)
                    off += a.size
                }
                accumulate.clear()
                total = 0

                val shortCount = chunk.size / 2
                val shorts = ShortArray(shortCount)
                ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                val hasSpeech = vad?.isSpeech(shorts, shortCount, SAMPLE_RATE) ?: true
                if (!hasSpeech) continue

                val text = try {
                    WhisperCppSttClient.transcribePcm16(chunk, SAMPLE_RATE, 1)
                } catch (e: Exception) {
                    Log.w(TAG, "Whisper transcribe failed: ${e.message}", e)
                    null
                }
                if (!text.isNullOrBlank()) {
                    Log.d(TAG, "Whisper text: $text")
                    GuardianEventStore.addEvent(
                        GuardianEvent(source = "Call playback", content = text, score = 0, riskLevel = RiskLevel.SAFE)
                    )
                    launch(Dispatchers.Main) { overlay.updateLiveTranscript(text) }
                }
            }
        }
    }

    private fun stopCapture() {
        if (isStopping) return
        isStopping = true
        isServiceRunning = false
        captureJob?.cancel()
        captureJob = null
        try { audioRecord?.stop() } catch (_: Exception) { }
        try { audioRecord?.release() } catch (_: Exception) { }
        audioRecord = null
        try { mediaProjection?.stop() } catch (_: Exception) { }
        mediaProjection = null
        try { vad?.close() } catch (_: Exception) { }
        vad = null
        overlay.dismiss()
        try { stopForeground(true) } catch (_: Exception) { }
        stopSelf()
        isStopping = false
    }

    private fun broadcastFallback() {
        sendBroadcast(Intent(ACTION_CALL_CAPTURE_FALLBACK_MIC).setPackage(packageName))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isStopping = false
        stopCapture()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_call_capture),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title_call_capture))
            .setContentText(getString(R.string.notification_text_call_capture))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CallPlaybackCapture"
        private const val CHANNEL_ID = "CallPlaybackCaptureChannel"
        private const val NOTIFICATION_ID = 2002
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SEC = 2

        const val ACTION_START = "com.sentinel.ai.service.action.START_CALL_PLAYBACK_CAPTURE"
        const val ACTION_STOP = "com.sentinel.ai.service.action.STOP_CALL_PLAYBACK_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        const val ACTION_CALL_PLAYBACK_CAPTURE_STARTED = "com.sentinel.ai.CALL_PLAYBACK_CAPTURE_STARTED"
        const val ACTION_CALL_CAPTURE_FALLBACK_MIC = "com.sentinel.ai.CALL_CAPTURE_FALLBACK_MIC"

        @Volatile
        var isServiceRunning = false
            private set

        fun start(context: Context, resultCode: Int, projectionData: Intent) {
            val i = Intent(context, CallPlaybackCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, projectionData)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CallPlaybackCaptureService::class.java).apply { action = ACTION_STOP })
        }
    }
}

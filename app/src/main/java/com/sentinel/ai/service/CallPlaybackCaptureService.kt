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
import com.sentinel.ai.ai.RawSttClient
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.security.ScamKeywordMatcher
import com.sentinel.ai.utils.MediaProjectionHolder
import com.sentinel.ai.utils.MediaProjectionStore
import com.sentinel.ai.utils.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * During a call (incoming or outgoing): captures playback audio via MediaProjection,
 * buffers 3s chunks with 1s overlap, sends to WhisperCppSttClient (https://voice.smarthomeus3r.space/stt)
 * — same API as debug mode. Real-time STT: overlay + GuardianEventStore.
 */
class CallPlaybackCaptureService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var overlay: OverlayController

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile private var isStopping = false
    // When true, the MediaProjection came from MediaProjectionHolder and must NOT be stopped
    // between calls so it can be reused on Android 14+ (single-use token).
    private var usingHeldProjection = false
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
            ACTION_START_HELD -> {
                // Use the MediaProjection object already stored in MediaProjectionHolder.
                // No user prompt needed — this is the normal path after setup.
                val held = MediaProjectionHolder.get()
                if (held != null) {
                    startCaptureWithProjection(held, fromHolder = true)
                } else {
                    // Holder is empty (process was killed). Try cached (resultCode, data) or fallback.
                    val cached = MediaProjectionStore.get()
                    if (cached != null) {
                        startCapture(cached.first, cached.second)
                    } else {
                        Log.w(TAG, "No stored projection available")
                        broadcastFallback()
                        stopSelf()
                    }
                }
                START_NOT_STICKY
            }
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
        // Create MediaProjection from (resultCode, data). Also cache the object in
        // MediaProjectionHolder so Android 14+ single-use tokens are not wasted on
        // the next call.
        val mp = try {
            mediaProjectionManager.getMediaProjection(resultCode, projectionData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}", e)
            broadcastFallback()
            stopSelf()
            return
        }
        MediaProjectionHolder.store(mp)   // cache for reuse
        startCaptureWithProjection(mp, fromHolder = false)
    }

    private fun startCaptureWithProjection(mp: MediaProjection, fromHolder: Boolean) {
        if (captureJob?.isActive == true) return

        vad = try { SileroVad(this) } catch (e: Exception) {
            Log.w(TAG, "Silero VAD init failed: ${e.message}", e)
            null
        }
        vad?.reset()

        usingHeldProjection = fromHolder
        mediaProjection = mp.also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system")
                    // If the system stops the projection, clear the holder too
                    if (usingHeldProjection) MediaProjectionHolder.release()
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
        sendBroadcast(Intent(ACTION_CALL_PLAYBACK_CAPTURE_STARTED).setPackage(packageName))
        overlay.showLiveTranscript(getString(R.string.overlay_listening))

        // 3s chunks, 1s overlap → advance 2s per chunk. Send to https://voice.smarthomeus3r.space/stt (same as debug).
        val buffer = ByteArrayOutputStream()

        captureJob = serviceScope.launch {
            val buf = ByteArray(bufferSize)
            while (isActive) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read <= 0) {
                    if (read < 0) Log.w(TAG, "AudioRecord read=$read")
                    continue
                }
                buffer.write(buf, 0, read)

                while (buffer.size() >= CHUNK_BYTES) {
                    val arr = buffer.toByteArray()
                    val chunk = arr.copyOfRange(0, CHUNK_BYTES)
                    buffer.reset()
                    buffer.write(arr, STEP_BYTES, arr.size - STEP_BYTES)

                    val shortCount = chunk.size / 2
                    val shorts = ShortArray(shortCount)
                    ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                    val hasSpeech = vad?.isSpeech(shorts, shortCount, SAMPLE_RATE) ?: true
                    if (!hasSpeech) continue

                    launch {
                        val text = try {
                            RawSttClient.transcribePcm16(chunk)
                        } catch (e: Exception) {
                            Log.w(TAG, "Raw STT failed: ${e.message}", e)
                            null
                        }
                        if (!text.isNullOrBlank()) {
                            Log.d(TAG, "Whisper text: $text")
                            GuardianEventStore.addEvent(
                                GuardianEvent(source = "Call playback", content = text, score = 0, riskLevel = RiskLevel.SAFE)
                            )
                            withContext(Dispatchers.Main) { overlay.updateLiveTranscript(text) }
                            val m = ScamKeywordMatcher.get(this@CallPlaybackCaptureService).match(text)
                            if (m != null) {
                                GuardianEventStore.addEvent(
                                    GuardianEvent(source = "Scam keyword", content = "${m.scenarioName}: ${m.matchedKeyword} | $text", score = 80, riskLevel = RiskLevel.CRITICAL)
                                )
                                withContext(Dispatchers.Main) { overlay.updateLiveTranscriptRisk(RiskLevel.CRITICAL, m.scenarioName) }
                            }
                        }
                    }
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
        // Keep the MediaProjection alive when it came from MediaProjectionHolder so
        // it can be reused for the next call without a new user prompt (critical on Android 14+
        // where the createScreenCaptureIntent grant is single-use).
        if (!usingHeldProjection) {
            try { mediaProjection?.stop() } catch (_: Exception) { }
        }
        mediaProjection = null
        usingHeldProjection = false
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
        private const val CHUNK_SEC = 3
        private const val OVERLAP_SEC = 1
        private val CHUNK_BYTES = SAMPLE_RATE * 2 * CHUNK_SEC      // 3s @ 16kHz 16bit mono
        private val STEP_BYTES = SAMPLE_RATE * 2 * (CHUNK_SEC - OVERLAP_SEC)  // 2s, 1s overlap

        const val ACTION_START = "com.sentinel.ai.service.action.START_CALL_PLAYBACK_CAPTURE"
        /** Start using the MediaProjection already stored in MediaProjectionHolder — no user prompt. */
        const val ACTION_START_HELD = "com.sentinel.ai.service.action.START_CALL_PLAYBACK_CAPTURE_HELD"
        const val ACTION_STOP = "com.sentinel.ai.service.action.STOP_CALL_PLAYBACK_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        const val ACTION_CALL_PLAYBACK_CAPTURE_STARTED = "com.sentinel.ai.CALL_PLAYBACK_CAPTURE_STARTED"
        const val ACTION_CALL_CAPTURE_FALLBACK_MIC = "com.sentinel.ai.CALL_CAPTURE_FALLBACK_MIC"

        @Volatile
        var isServiceRunning = false
            private set

        /**
         * Start capture using the MediaProjection already stored in [MediaProjectionHolder].
         * This is the preferred path — no user prompt is shown.
         */
        fun startHeld(context: Context) {
            val i = Intent(context, CallPlaybackCaptureService::class.java).apply {
                action = ACTION_START_HELD
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start (held): ${e.message}", e)
            }
        }

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

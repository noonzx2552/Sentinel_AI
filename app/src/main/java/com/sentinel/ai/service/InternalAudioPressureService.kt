package com.sentinel.ai.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sentinel.ai.R
import com.sentinel.ai.ui.DashboardActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that captures internal audio via MediaProjection and forwards transcripts.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class InternalAudioPressureService : Service(), PressureTranscriptListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private var speechEngine: PressureSpeechEngine? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val projectionData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        ensureForeground()
        startCapture(resultCode, projectionData)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        speechEngine?.shutdown()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onTranscript(text: String) {
        val intent = Intent(ACTION_PRESSURE_TRANSCRIPT).apply {
            putExtra(EXTRA_TRANSCRIPT, text)
        }
        sendBroadcast(intent)
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        foregroundStarted = true
    }

    private fun startCapture(resultCode: Int, projectionData: Intent?) {
        if (audioRecord != null) return
        if (projectionData == null) {
            stopSelf()
            return
        }
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData)
        val projection = mediaProjection ?: run {
            stopSelf()
            return
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(DEFAULT_BUFFER)

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize * 2)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            stopSelf()
            return
        }

        try {
            audioRecord?.startRecording()
        } catch (_: Exception) {
            stopSelf()
            return
        }

        if (speechEngine == null) {
            speechEngine = PressureSpeechEngine(this)
        }

        serviceScope.launch {
            val buffer = ByteArray(bufferSize)
            while (isActive && audioRecord != null) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    speechEngine?.submitPcmChunk(buffer.copyOf(read))
                } else {
                    delay(10)
                }
            }
        }
    }

    private fun stopCapture() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, DashboardActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pressure Monitor")
            .setContentText("Monitoring internal audio for pressure transcripts.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "PressureMonitor",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Internal audio pressure monitoring"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val ACTION_PRESSURE_TRANSCRIPT = "com.sentinel.ai.ACTION_PRESSURE_TRANSCRIPT"
        const val EXTRA_TRANSCRIPT = "extra_pressure_transcript"
        const val ACTION_START = "com.sentinel.ai.action.PRESSURE_START"
        const val ACTION_STOP = "com.sentinel.ai.action.PRESSURE_STOP"
        const val EXTRA_RESULT_CODE = "extra_media_projection_result"
        const val EXTRA_RESULT_DATA = "extra_media_projection_data"
        private const val SAMPLE_RATE = 44100
        private const val DEFAULT_BUFFER = 8192
        private const val CHANNEL_ID = "pressure_monitor"
        private const val NOTIFICATION_ID = 9001

        fun start(context: Context, data: Intent, resultCode: Int) {
            val intent = Intent(context, InternalAudioPressureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

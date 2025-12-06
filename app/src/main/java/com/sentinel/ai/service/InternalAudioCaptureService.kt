package com.sentinel.ai.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.sentinel.ai.stt.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A foreground service for capturing internal audio using MediaProjection and transcribing it in real-time.
 */
class InternalAudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "Service started with a null intent. Stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data: Intent? = intent.getParcelableExtra(EXTRA_PROJECTION_DATA)

                if (resultCode != -1 && data != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    startCapture(resultCode, data)
                } else {
                    Log.e(TAG, "Result code or projection data is missing. Cannot start capture.")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP -> {
                stopCapture()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(resultCode: Int, projectionData: Intent) {
        if (captureJob?.isActive == true) {
            Log.w(TAG, "Capture is already in progress.")
            return
        }

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, projectionData)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped. Stopping capture.")
                stopCapture()
            }
        }, null)

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(WhisperEngine.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(WhisperEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        audioRecord?.startRecording()

        captureJob = serviceScope.launch {
            val audioBuffer = ByteArray(bufferSize)
            while (isActive) {
                val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (bytesRead > 0) {
                    Log.d(TAG, "Read $bytesRead bytes from AudioRecord.")
                    // Send a copy of the buffer for transcription
                    val transcript = WhisperEngine.transcribe(audioBuffer.clone())
                    if (!transcript.isNullOrEmpty()) {
                        broadcastTranscript(transcript)
                    }
                }
            }
        }
        Log.d(TAG, "Internal audio capture started.")
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null

        audioRecord?.apply {
            try {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping or releasing AudioRecord", e)
            }
        }
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null

        Log.d(TAG, "Internal audio capture stopped.")
        stopForeground(true)
        stopSelf()
    }

    private fun broadcastTranscript(text: String) {
        val intent = Intent(ACTION_TRANSCRIPT).apply {
            putExtra(EXTRA_TRANSCRIPT_TEXT, text)
            setPackage(packageName) // Ensure broadcast is internal to the app
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Internal Audio Transcription",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for the real-time audio transcription service."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Transcription Service Active")
            .setContentText("Capturing internal audio for transcription.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a proper icon
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "InternalAudioCaptureSvc"
        private const val NOTIFICATION_CHANNEL_ID = "InternalAudioCaptureChannel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_TRANSCRIPT = "com.sentinel.ai.ACTION_TRANSCRIPT"
        const val EXTRA_TRANSCRIPT_TEXT = "extra_text"

        private const val ACTION_START = "com.sentinel.ai.service.action.START_CAPTURE"
        private const val ACTION_STOP = "com.sentinel.ai.service.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        fun start(context: Context, resultCode: Int, projectionData: Intent) {
            val intent = Intent(context, InternalAudioCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, projectionData)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, InternalAudioCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

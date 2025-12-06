package com.sentinel.ai.service

import android.annotation.SuppressLint
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
import androidx.core.app.NotificationCompat
import com.sentinel.ai.R
import com.sentinel.ai.ai.OfflineStt
import com.sentinel.ai.ai.OfflineStt.StreamingSession
import com.sentinel.ai.utils.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log
import android.widget.Toast

class InternalAudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var hasLoggedAudioDetection = false
    private var hasShownOverlayDetection = false
    private var recordingFile: File? = null
    private var recordingStream: FileOutputStream? = null
    private var totalPcmBytes: Long = 0
    private var sttSession: StreamingSession? = null

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var overlayManager: OverlayManager
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        overlayManager = OverlayManager(this)
        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            when (intent.action) {
                ACTION_START -> {
                    // Call startForeground ASAP to satisfy the 5s timeout requirement
                    startForeground(NOTIFICATION_ID, createNotification())

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
                        broadcast(ACTION_CAPTURE_ERROR, EXTRA_ERROR_MESSAGE, "Missing projection data")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
                ACTION_STOP -> {
                    stopCapture()
                }
            }
            START_STICKY
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Foreground service start not allowed: ${e.message}", e)
                broadcast(ACTION_CAPTURE_ERROR, EXTRA_ERROR_MESSAGE, "Foreground service start blocked: ${e.message}")
            } else {
                Log.e(TAG, "Unexpected error in onStartCommand", e)
                broadcast(ACTION_CAPTURE_ERROR, EXTRA_ERROR_MESSAGE, e.message ?: "Unexpected error")
            }
            stopSelf()
            START_NOT_STICKY
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(resultCode: Int, projectionData: Intent) {
        if (captureJob?.isActive == true) return

        try {
            startRecordingFile()
            overlayManager.showOverlay()
            overlayManager.updateTranscript("Listening for audio...")
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, projectionData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system.")
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
                .setSampleRate(CAPTURE_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val bufferSize = AudioRecord.getMinBufferSize(CAPTURE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            audioRecord?.startRecording()
            isServiceRunning = true
            broadcast(ACTION_CAPTURE_STARTED, EXTRA_TRANSCRIPT_TEXT, "started")

            captureJob = serviceScope.launch {
                val audioBuffer = ByteArray(bufferSize)
                while (isActive) {
                    val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (bytesRead > 0) {
                        val rms = computeRms(audioBuffer, bytesRead)
                        val aboveThreshold = rms > MIN_RMS
                        if (aboveThreshold && !hasLoggedAudioDetection) {
                            Log.d(TAG, "Audio detected from playback capture (bytes=$bytesRead, rms=$rms)")
                            hasLoggedAudioDetection = true
                            if (!hasShownOverlayDetection) {
                                launch(Dispatchers.Main) {
                                    overlayManager.updateTranscript("Audio detected")
                                }
                                hasShownOverlayDetection = true
                            }
                        }
                        // Persist raw PCM into WAV file
                        recordingStream?.write(audioBuffer, 0, bytesRead)
                        totalPcmBytes += bytesRead
                        // Local STT (offline Vosk if model available)
                        try {
                            val transcript = transcribeLocal(audioBuffer, bytesRead)
                            if (!transcript.isNullOrBlank()) {
                                broadcast(ACTION_TRANSCRIPT, EXTRA_TRANSCRIPT_TEXT, transcript)
                                launch(Dispatchers.Main) {
                                    overlayManager.updateTranscript(transcript)
                                }
                            } else {
                                Log.d(TAG, "Local STT returned empty (bytes=$bytesRead)")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Local STT failed: ${e.message}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            broadcast(ACTION_CAPTURE_ERROR, EXTRA_ERROR_MESSAGE, e.message ?: "Unknown error")
            stopCapture()
        }
    }

    private fun stopCapture() {
        isServiceRunning = false
        captureJob?.cancel()
        captureJob = null
        sttSession?.close()
        sttSession = null
        stopRecordingFile()

        audioRecord?.apply {
            try {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            } catch (e: IllegalStateException) {
                 Log.e(TAG, "Failed to stop AudioRecord", e)
            }
        }
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null

        overlayManager.removeOverlay()
        stopForeground(true)
        stopSelf()
    }

    private fun startRecordingFile() {
        hasLoggedAudioDetection = false
        hasShownOverlayDetection = false
        totalPcmBytes = 0
        try {
            val dir = externalCacheDir ?: cacheDir
            val file = File(dir, "capture_${System.currentTimeMillis()}.wav")
            recordingFile = file
            val stream = FileOutputStream(file)
            recordingStream = stream
            writeWavHeader(stream, CAPTURE_SAMPLE_RATE, 2, 16)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create recording file", e)
            broadcast(ACTION_CAPTURE_ERROR, EXTRA_ERROR_MESSAGE, "Cannot create recording file: ${e.message}")
        }
    }

    private fun stopRecordingFile() {
        try {
            recordingStream?.flush()
            recordingStream?.close()
        } catch (_: Exception) { }
        recordingStream = null

        recordingFile?.let { file ->
            try {
                finalizeWavFile(file, totalPcmBytes)
                if (totalPcmBytes > 0) {
                    broadcast(ACTION_CAPTURE_SAVED, EXTRA_RECORDING_PATH, file.absolutePath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finalize recording file", e)
            }
            Unit
        }
        recordingFile = null
        totalPcmBytes = 0
        hasLoggedAudioDetection = false
        hasShownOverlayDetection = false
    }

    private fun writeWavHeader(
        stream: FileOutputStream,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int
    ) {
        val byteRate = sampleRate * channels * bitDepth / 8
        val buffer = ByteBuffer.allocate(44)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(0) // placeholder for file size
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1.toShort()) // AudioFormat PCM = 1
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitDepth / 8).toShort()) // Block align
        buffer.putShort(bitDepth.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(0) // placeholder for data size
        stream.write(buffer.array())
    }

    private fun finalizeWavFile(file: File, dataSize: Long) {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intToLE((dataSize + 36).toInt()))
            raf.seek(40)
            raf.write(intToLE(dataSize.toInt()))
        }
    }

    private fun intToLE(value: Int): ByteArray {
        val buffer = ByteBuffer.allocate(4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(value)
        return buffer.array()
    }

    private fun transcribeLocal(buffer: ByteArray, size: Int): String? {
        val session = ensureSttSession() ?: return null
        val chunk = if (size == buffer.size) buffer else buffer.copyOf(size)
        return session.accept(chunk, chunk.size)
    }

    private fun ensureSttSession(): StreamingSession? {
        val existing = sttSession
        if (existing != null) return existing
        val created = OfflineStt.createStreamingSession(
            context = this,
            sourceSampleRate = audioRecord?.sampleRate ?: CAPTURE_SAMPLE_RATE,
            isStereo = true
        )
        sttSession = created
        return created
    }

    private fun computeRms(buffer: ByteArray, size: Int): Int {
        if (size < 2) return 0
        var sum = 0L
        var count = 0
        var i = 0
        while (i + 1 < size) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += (sample * sample).toLong()
            count++
            i += 2
        }
        if (count == 0) return 0
        val mean = sum / count
        return kotlin.math.sqrt(mean.toDouble()).toInt()
    }

    private fun broadcast(action: String, extraKey: String, message: String) {
        val intent = Intent(action).apply {
            putExtra(extraKey, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Audio Transcription", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Transcription Service Active")
            .setContentText("Capturing internal audio...")
            .setSmallIcon(R.mipmap.ic_launcher) // Use a standard mipmap icon for safety
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

        @Volatile
        var isServiceRunning = false
            private set

        private const val NOTIFICATION_CHANNEL_ID = "InternalAudioCaptureChannel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_TRANSCRIPT = "com.sentinel.ai.ACTION_TRANSCRIPT"
        const val ACTION_CAPTURE_STARTED = "com.sentinel.ai.ACTION_CAPTURE_STARTED"
        const val EXTRA_TRANSCRIPT_TEXT = "extra_text"
        const val ACTION_CAPTURE_ERROR = "com.sentinel.ai.ACTION_CAPTURE_ERROR"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val ACTION_CAPTURE_SAVED = "com.sentinel.ai.ACTION_CAPTURE_SAVED"
        const val EXTRA_RECORDING_PATH = "extra_recording_path"
        private const val MIN_RMS = 500
        // Capture at higher rate for clearer playback, downsampled for STT
        private const val CAPTURE_SAMPLE_RATE = 48000

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
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
                    Log.e(TAG, "Foreground service start blocked: ${e.message}", e)
                    Toast.makeText(context.applicationContext, "Cannot start capture in background. Please reopen the app.", Toast.LENGTH_LONG).show()
                } else {
                    Log.e(TAG, "Failed to start capture service", e)
                    Toast.makeText(context.applicationContext, "Cannot start capture: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, InternalAudioCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}


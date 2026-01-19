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
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.sentinel.ai.R
import com.sentinel.ai.ai.OfflineStt
import com.sentinel.ai.ai.SileroVad
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

/**
 * Foreground service that captures internal audio via MediaProjection and streams it to Vosk.
 *
 * How to start from an Activity:
 *   InternalAudioCaptureService.start(context, resultCode, dataIntentFromMediaProjection)
 *   // where resultCode/dataIntent come from MediaProjectionManager.createScreenCaptureIntent()
 */
class InternalAudioCaptureService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var overlayManager: OverlayManager

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    @Volatile private var isStopping = false
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var recordingFile: File? = null
    private var recordingStream: FileOutputStream? = null
    private var totalPcmBytes: Long = 0
    private var vad: SileroVad? = null

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        overlayManager = OverlayManager(this)
        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        // Always enter foreground immediately to avoid 5s ANR limit.
        startForeground(NOTIFICATION_ID, createNotification())

        return try {
            when (intent.action) {
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
                        broadcast(ACTION_CAPTURE_ERROR, EXTRA_ERROR_MESSAGE, "Missing projection data")
                        stopSelf()
                    }
                }
                ACTION_STOP -> stopCapture()
            }
            START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed: ${e.message}", e)
            broadcast(ACTION_CAPTURE_ERROR, EXTRA_ERROR_MESSAGE, e.message ?: "Error")
            stopSelf()
            START_NOT_STICKY
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(resultCode: Int, projectionData: Intent) {
        if (captureJob?.isActive == true) return

        // Warm-load model early to catch errors up front.
            try {
                OfflineStt.ensureModel(this)
            } catch (e: Exception) {
                Log.e(TAG, "Vosk model load failed: ${e.message}", e)
                Toast.makeText(
                    applicationContext,
                    getString(R.string.vosk_model_failed_format, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            broadcast(ACTION_CAPTURE_ERROR, EXTRA_ERROR_MESSAGE, "Model load failed")
            stopSelf()
            return
        }

        overlayManager.showOverlay()
        overlayManager.updatePlaybackTranscript(getString(R.string.overlay_listening))
        startRecordingFile()

        if (vad == null) {
            vad = try {
                SileroVad(this)
            } catch (e: Exception) {
                Log.w(TAG, "Silero VAD init failed: ${e.message}", e)
                null
            }
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
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(CAPTURE_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start failed: ${e.message}", e)
            broadcast(ACTION_CAPTURE_ERROR, EXTRA_ERROR_MESSAGE, "AudioRecord start failed")
            stopCapture()
            return
        }

        isServiceRunning = true
        broadcast(ACTION_CAPTURE_STARTED, EXTRA_TRANSCRIPT_TEXT, "started")

        captureJob = serviceScope.launch {
            val byteBuffer = ByteArray(bufferSize)
            while (isActive) {
                val read = audioRecord?.read(byteBuffer, 0, byteBuffer.size) ?: 0
                if (read <= 0) {
                    Log.w(TAG, "AudioRecord read returned $read bytes")
                    continue
                }
                recordingStream?.write(byteBuffer, 0, read)
                totalPcmBytes += read.toLong()

                // Convert little-endian bytes -> shorts
                val shortCount = read / 2
                val shorts = ShortArray(shortCount)
                ByteBuffer.wrap(byteBuffer, 0, read)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(shorts)

                val hasSpeech = vad?.isSpeech(shorts, shortCount, CAPTURE_SAMPLE_RATE) ?: true
                if (!hasSpeech) {
                    continue
                }

                val text = try {
                    OfflineStt.recognizePcm16(
                        context = this@InternalAudioCaptureService,
                        audio = shorts,
                        size = shortCount,
                        sampleRate = CAPTURE_SAMPLE_RATE
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Vosk recognize failed: ${e.message}", e)
                    null
                }

                if (!text.isNullOrBlank()) {
                    Log.d(TAG, "Vosk text: $text")
                    broadcast(ACTION_TRANSCRIPT, EXTRA_TRANSCRIPT_TEXT, text)
                    launch(Dispatchers.Main) { overlayManager.updatePlaybackTranscript(text) }
                } else {
                    Log.d(TAG, "Vosk empty result (bytes=$read)")
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
        try {
            recordingStream?.flush()
        } catch (_: Exception) { }
        recordingStream = null

        audioRecord?.apply {
            try {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
            } catch (_: Exception) { }
            try { release() } catch (_: Exception) { }
        }
        audioRecord = null

        try { mediaProjection?.stop() } catch (_: Exception) { }
        mediaProjection = null

        try { vad?.close() } catch (_: Exception) { }
        vad = null

        finalizeRecordingFile()
        overlayManager.removeOverlay()
        try { stopForeground(true) } catch (_: Exception) { }
        stopSelf()
        isStopping = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isStopping = false
        stopCapture()
        super.onDestroy()
    }

    private fun startRecordingFile() {
        totalPcmBytes = 0
        recordingFile = null
        recordingStream = null
        try {
            val dir = externalCacheDir ?: cacheDir
            val file = File(dir, "capture_${System.currentTimeMillis()}.wav")
            recordingFile = file
            val stream = FileOutputStream(file)
            recordingStream = stream
            writeWavHeader(stream, CAPTURE_SAMPLE_RATE, 1, 16)
        } catch (e: Exception) {
            Log.w(TAG, "startRecordingFile failed: ${e.message}", e)
        }
    }

    private fun finalizeRecordingFile() {
        val file = recordingFile ?: return
        try {
            finalizeWavFile(file, totalPcmBytes)
            broadcast(ACTION_CAPTURE_SAVED, EXTRA_RECORDING_PATH, file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "finalizeRecordingFile failed: ${e.message}", e)
        }
    }

    private fun writeWavHeader(stream: FileOutputStream, sampleRate: Int, channels: Int, bitDepth: Int) {
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

    private fun broadcast(action: String, extraKey: String, message: String) {
        val intent = Intent(action).apply {
            putExtra(extraKey, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_transcription),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title_transcription_active))
            .setContentText(getString(R.string.notification_text_capturing_audio))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
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

        private const val CAPTURE_SAMPLE_RATE = 16000

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
                Log.e(TAG, "Failed to start service: ${e.message}", e)
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.capture_start_failed_format, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
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

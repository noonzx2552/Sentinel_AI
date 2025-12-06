package com.sentinel.ai.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.os.Looper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sentinel.ai.R
import com.sentinel.ai.ai.OfflineStt
import com.sentinel.ai.service.InternalAudioCaptureService
import java.io.ByteArrayOutputStream
import java.io.File
import com.sentinel.ai.utils.WavUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Recognizer

class DashboardActivity : AppCompatActivity() {

    private lateinit var startCaptureButton: Button
    private lateinit var stopCaptureButton: Button
    private lateinit var playRecordingButton: Button
    private lateinit var transcribeRecordingButton: Button
    private lateinit var testMicButton: Button
    private lateinit var systemSttButton: Button
    private lateinit var clearTranscriptButton: Button
    private lateinit var transcriptTextView: TextView
    private lateinit var statusTextView: TextView

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private var isReceiverRegistered = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var lastRecordingPath: String? = null
    private var mediaPlayer: MediaPlayer? = null
    private var offlineLiveJob: kotlinx.coroutines.Job? = null
    private var offlineLiveRecord: AudioRecord? = null
    private var offlineLiveRecognizer: Recognizer? = null
    private var systemSttRecord: AudioRecord? = null
    private var systemSttRecordingJob: kotlinx.coroutines.Job? = null
    @Volatile private var isSystemSttRecording: Boolean = false
    private var systemSttBuffer: java.io.ByteArrayOutputStream? = null

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) {
            continueStartCaptureProcess()
        } else {
            Toast.makeText(this, "Audio and Notification permissions are required.", Toast.LENGTH_LONG).show()
            setStatus("Idle")
        }
    }

    private val appEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                InternalAudioCaptureService.ACTION_TRANSCRIPT -> {
                    val transcript = intent.getStringExtra(InternalAudioCaptureService.EXTRA_TRANSCRIPT_TEXT)
                    if (!transcript.isNullOrEmpty()) {
                        val newText = "${transcriptTextView.text}\n$transcript"
                        transcriptTextView.text = newText
                    }
                }
                InternalAudioCaptureService.ACTION_CAPTURE_STARTED -> {
                    setStatus("Capturing audio (runs in background)")
                    startCaptureButton.isEnabled = false
                    stopCaptureButton.isEnabled = true
                }
                InternalAudioCaptureService.ACTION_CAPTURE_SAVED -> {
                    val path = intent.getStringExtra(InternalAudioCaptureService.EXTRA_RECORDING_PATH)
                    if (!path.isNullOrBlank()) {
                        lastRecordingPath = path
                        Toast.makeText(context, "Recording saved", Toast.LENGTH_SHORT).show()
                        setStatus("Recording saved")
                        updatePlayButtonState()
                    }
                }
                InternalAudioCaptureService.ACTION_CAPTURE_ERROR -> {
                    val errorMessage = intent.getStringExtra(InternalAudioCaptureService.EXTRA_ERROR_MESSAGE)
                    Toast.makeText(context, "Capture failed: $errorMessage", Toast.LENGTH_LONG).show()
                    setStatus("Idle")
                    updateUiState()
                }
            }
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            startMediaProjectionRequest()
        } else {
            Toast.makeText(this, "Overlay permission is required.", Toast.LENGTH_LONG).show()
            setStatus("Idle")
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Toast.makeText(this, "MediaProjection permission granted. Starting service...", Toast.LENGTH_SHORT).show()
            InternalAudioCaptureService.start(this, result.resultCode, result.data!!)
            setStatus("Starting capture service...")
            startCaptureButton.isEnabled = false
            stopCaptureButton.isEnabled = true
        } else {
            Toast.makeText(this, "MediaProjection permission was denied.", Toast.LENGTH_SHORT).show()
            setStatus("Idle")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        startCaptureButton = findViewById(R.id.btnStartCapture)
        stopCaptureButton = findViewById(R.id.btnStopCapture)
        playRecordingButton = findViewById(R.id.btnPlayRecording)
        transcribeRecordingButton = findViewById(R.id.btnTranscribeRecording)
        testMicButton = findViewById(R.id.btnTestMic)
        systemSttButton = findViewById(R.id.btnSystemStt)
        clearTranscriptButton = findViewById(R.id.btnClearTranscript)
        transcriptTextView = findViewById(R.id.tvTranscript)
        statusTextView = findViewById(R.id.tvStatus)

        startCaptureButton.setOnClickListener { startCaptureProcess() }
        stopCaptureButton.setOnClickListener { stopCaptureProcess() }
        playRecordingButton.setOnClickListener { playLastRecording() }
        transcribeRecordingButton.setOnClickListener { transcribeLastRecording() }
        testMicButton.setOnClickListener { runMicTest() }
        testMicButton.setOnLongClickListener {
            toggleLiveOfflineStt()
            true
        }
        systemSttButton.setOnClickListener { startSystemSpeechToText() }
        clearTranscriptButton.setOnClickListener { transcriptTextView.text = "" }
        setStatus("Idle")
        updatePlayButtonState()
    }

    override fun onResume() {
        super.onResume()
        registerAppEventsReceiver()
        updateUiState()
    }

    override fun onPause() {
        super.onPause()
        unregisterAppEventsReceiver()
    }

    private fun updateUiState() {
        val isRunning = InternalAudioCaptureService.isServiceRunning
        startCaptureButton.isEnabled = !isRunning
        stopCaptureButton.isEnabled = isRunning
        testMicButton.isEnabled = !isRunning
        systemSttButton.isEnabled = !isRunning
        transcribeRecordingButton.isEnabled = !isRunning
        setStatus(if (isRunning) "Capturing audio (runs in background)" else "Idle")
        updatePlayButtonState()
    }

    private fun setStatus(text: String) {
        statusTextView.text = "Status: $text"
    }

    private fun startCaptureProcess() {
        val allPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allPermissionsGranted) {
            setStatus("Starting capture...")
            continueStartCaptureProcess()
        } else {
            setStatus("Waiting for permissions...")
            permissionsLauncher.launch(requiredPermissions)
        }
    }

    private fun continueStartCaptureProcess() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Waiting for overlay permission...")
            requestOverlayPermission()
        } else {
            setStatus("Requesting screen capture...")
            startMediaProjectionRequest()
        }
    }

    private fun stopCaptureProcess() {
        InternalAudioCaptureService.stop(this)
        Toast.makeText(this, "Capture service stopped.", Toast.LENGTH_SHORT).show()
        setStatus("Stopped")
        updateUiState()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        overlayPermissionLauncher.launch(intent)
    }

    private fun startMediaProjectionRequest() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun registerAppEventsReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(InternalAudioCaptureService.ACTION_TRANSCRIPT)
            addAction(InternalAudioCaptureService.ACTION_CAPTURE_STARTED)
            addAction(InternalAudioCaptureService.ACTION_CAPTURE_SAVED)
            addAction(InternalAudioCaptureService.ACTION_CAPTURE_ERROR)
        }
        // Always register as not exported to satisfy runtime broadcast flag requirements
        ContextCompat.registerReceiver(
            this,
            appEventsReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true
    }

    private fun unregisterAppEventsReceiver() {
        if (!isReceiverRegistered) return
        try {
            unregisterReceiver(appEventsReceiver)
            isReceiverRegistered = false
        } catch (e: IllegalArgumentException) { /* Already unregistered */ }
    }

    private fun updatePlayButtonState() {
        val applyState = {
            val exists = lastRecordingPath?.let { File(it).exists() } == true
            playRecordingButton.isEnabled = exists && !InternalAudioCaptureService.isServiceRunning
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyState()
        } else {
            runOnUiThread { applyState() }
        }
    }

    private fun playLastRecording() {
        val path = lastRecordingPath
        if (path.isNullOrBlank()) {
            Toast.makeText(this, "No recording available yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Recording file not found.", Toast.LENGTH_SHORT).show()
            lastRecordingPath = null
            updatePlayButtonState()
            return
        }
        try {
            releasePlayer()
            val player = MediaPlayer()
            player.setDataSource(path)
            player.setOnCompletionListener {
                setStatus("Idle")
                releasePlayer()
                updatePlayButtonState()
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            setStatus("Playing last recording")
            updatePlayButtonState()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play recording: ${e.message}", Toast.LENGTH_LONG).show()
            releasePlayer()
            updatePlayButtonState()
        }
    }

    private fun transcribeLastRecording() {
        val path = lastRecordingPath
        if (path.isNullOrBlank()) {
            Toast.makeText(this, "No recording available yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Recording file not found.", Toast.LENGTH_SHORT).show()
            lastRecordingPath = null
            updatePlayButtonState()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            if (!OfflineStt.ensureModel(this@DashboardActivity)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DashboardActivity, "Offline model missing. Check assets/models/vosk-model.zip.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val data = file.readBytes()
            if (data.size < 44) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DashboardActivity, "Recording is too short/invalid.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            try {
                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val channels = bb.getShort(22).toInt()
                val sampleRate = bb.getInt(24)
                val bitsPerSample = bb.getShort(34).toInt()
                val dataStart = 44
                val pcm = data.copyOfRange(dataStart, data.size)
                Log.d("DashboardActivity", "Transcribing file sr=$sampleRate ch=$channels bits=$bitsPerSample size=${pcm.size}")

                val transcript = OfflineStt.transcribePcm16(
                    context = this@DashboardActivity,
                    audio = pcm,
                    sampleRate = sampleRate,
                    isStereo = channels >= 2
                ).orEmpty()

                withContext(Dispatchers.Main) {
                    if (transcript.isNotBlank()) {
                        transcriptTextView.append("\n[File STT] $transcript")
                        setStatus("File STT: \"$transcript\"")
                    } else {
                        setStatus("File STT: no text")
                        Toast.makeText(this@DashboardActivity, "No text recognized from recording.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DashboardActivity, "Transcribe failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) { }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        releasePlayer()
        stopLiveOfflineStt()
        stopSystemSttRecording(saveAudio = false)
        super.onDestroy()
    }

    private fun toggleLiveOfflineStt() {
        if (offlineLiveJob?.isActive == true) {
            stopLiveOfflineStt()
        } else {
            startLiveOfflineStt()
        }
    }

    private fun startLiveOfflineStt() {
        if (InternalAudioCaptureService.isServiceRunning) {
            Toast.makeText(this, "Stop capture before testing mic.", Toast.LENGTH_SHORT).show()
            return
        }
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Toast.makeText(this, "Mic permission required.", Toast.LENGTH_SHORT).show()
            permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (!OfflineStt.ensureModel(this)) {
            Toast.makeText(this, "Offline model missing or failed to load. Check assets/models/vosk-model.zip.", Toast.LENGTH_LONG).show()
            setStatus("Live STT: model missing")
            return
        }
        stopLiveOfflineStt()
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
        offlineLiveRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        val rec = OfflineStt.createRecognizer(this, sampleRate)
        if (rec == null) {
            Toast.makeText(this, "Cannot create recognizer.", Toast.LENGTH_SHORT).show()
            return
        }
        offlineLiveRecognizer = rec
        try {
            offlineLiveRecord?.startRecording()
        } catch (e: Exception) {
            Log.w("DashboardActivity", "Live STT mic start failed: ${e.message}", e)
            Toast.makeText(this, "Cannot start mic: ${e.message}", Toast.LENGTH_LONG).show()
            stopLiveOfflineStt()
            return
        }
        testMicButton.text = "Stop Live STT"
        setStatus("Live offline STT running...")
        offlineLiveJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            while (isActive) {
                val read = try { offlineLiveRecord?.read(buffer, 0, buffer.size) ?: 0 } catch (_: Exception) { 0 }
                if (read > 0) {
                    val ok = rec.acceptWaveForm(buffer, read)
                    val json = if (ok) rec.result else rec.partialResult
                    val text = parseVoskText(json)
                    if (!text.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            transcriptTextView.append("\n[Offline Live] $text")
                            setStatus("Live offline STT: \"$text\"")
                        }
                    }
                }
            }
        }
    }

    private fun stopLiveOfflineStt() {
        offlineLiveJob?.cancel()
        offlineLiveJob = null
        try { offlineLiveRecord?.stop() } catch (_: Exception) { }
        offlineLiveRecord?.release()
        offlineLiveRecord = null
        try { offlineLiveRecognizer?.close() } catch (_: Exception) { }
        offlineLiveRecognizer = null
        testMicButton.text = "Test Mic (Vosk offline)"
        if (!InternalAudioCaptureService.isServiceRunning) setStatus("Idle")
    }

    private fun parseVoskText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            val key = if (json.contains("\"text\"")) "\"text\"" else "\"partial\""
            val idx = json.indexOf(key)
            if (idx == -1) return ""
            val start = json.indexOf(':', idx) + 1
            val end = json.indexOf('"', start + 1)
            val firstQuote = json.indexOf('"', start)
            if (firstQuote == -1 || end == -1 || end <= firstQuote) return ""
            json.substring(firstQuote + 1, end)
        } catch (e: Exception) {
            Log.w("DashboardActivity", "parseVoskText failed: ${e.message}", e)
            ""
        }
    }

    private fun runMicTest() {
        if (InternalAudioCaptureService.isServiceRunning) {
            Toast.makeText(this, "Stop capture before testing mic.", Toast.LENGTH_SHORT).show()
            return
        }
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Toast.makeText(this, "Mic permission required.", Toast.LENGTH_SHORT).show()
            permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (!OfflineStt.ensureModel(this)) {
            Toast.makeText(this, "Offline model missing or failed to load. Check assets/models/vosk-model.zip.", Toast.LENGTH_LONG).show()
            setStatus("Mic test: model missing")
            return
        }
        setStatus("Testing mic with offline STT...")
        testMicButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            val bufferSize = (minBuf.coerceAtLeast(2048))
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                encoding,
                bufferSize
            )
            val output = ByteArrayOutputStream()
            try {
                audioRecord.startRecording()
                val buffer = ByteArray(bufferSize)
                val targetDurationMs = 2500
                var capturedMs = 0
                val frameMs = bufferSize * 1000 / (sampleRate * 2) // 2 bytes per sample mono
                while (capturedMs < targetDurationMs) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        capturedMs += frameMs
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DashboardActivity, "Mic test failed: ${e.message}", Toast.LENGTH_LONG).show()
                    setStatus("Idle")
                    testMicButton.isEnabled = true
                }
                audioRecord.release()
                return@launch
            } finally {
                try { audioRecord.stop() } catch (_: Exception) { }
                audioRecord.release()
            }

            val audioData = output.toByteArray()
            val normalizedPcm = normalizePcm16(audioData)
            val rms = computeRms(normalizedPcm)
            Log.d("DashboardActivity", "Mic test captured bytes=${audioData.size} rms=$rms")
            val transcript = try {
                OfflineStt.transcribePcm16(
                    context = this@DashboardActivity,
                    audio = normalizedPcm,
                    sampleRate = sampleRate,
                    isStereo = false
                ) ?: ""
            } catch (e: Exception) {
                Log.w("DashboardActivity", "Mic test STT failed: ${e.message}", e)
                ""
            }

            // Save test audio to a WAV file for replay
            val wavData = WavUtil.pcmToWav(normalizedPcm, sampleRate, 1, 16)
            val testFile = File(cacheDir, "mic_test_${System.currentTimeMillis()}.wav")
            testFile.writeBytes(wavData)
            val savedPath = testFile.absolutePath

            withContext(Dispatchers.Main) {
                lastRecordingPath = savedPath
                if (transcript.isNotBlank()) {
                    transcriptTextView.append("\n[Test Mic] $transcript")
                    setStatus("Mic test: \"$transcript\"")
                } else {
                    setStatus("Mic test: no text")
                    Toast.makeText(this@DashboardActivity, "No text recognized (check offline model).", Toast.LENGTH_SHORT).show()
                }
                Toast.makeText(this@DashboardActivity, "Test audio saved for playback.", Toast.LENGTH_SHORT).show()
                updatePlayButtonState()
                testMicButton.isEnabled = true
            }
        }
    }

    private fun computeRms(buffer: ByteArray): Int {
        if (buffer.size < 2) return 0
        var sum = 0L
        var count = 0
        var i = 0
        while (i + 1 < buffer.size) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += (sample * sample).toLong()
            count++
            i += 2
        }
        if (count == 0) return 0
        val mean = sum / count
        return kotlin.math.sqrt(mean.toDouble()).toInt()
    }

    private fun normalizePcm16(input: ByteArray): ByteArray {
        if (input.size < 2) return input
        var maxAbs = 0
        var i = 0
        while (i + 1 < input.size) {
            val sample = ((input[i + 1].toInt() shl 8) or (input[i].toInt() and 0xFF)).toShort()
            val abs = kotlin.math.abs(sample.toInt())
            if (abs > maxAbs) maxAbs = abs
            i += 2
        }
        if (maxAbs == 0) return input
        val target = (Short.MAX_VALUE * 0.8).toInt()
        val gain = target.toFloat() / maxAbs.toFloat()
        val out = ByteArray(input.size)
        i = 0
        while (i + 1 < input.size) {
            val sample = ((input[i + 1].toInt() shl 8) or (input[i].toInt() and 0xFF)).toShort()
            val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            out[i] = (scaled.toInt() and 0xFF).toByte()
            out[i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    /**
     * Long-press "Test Mic" to use the built-in Android speech recognizer (no Vosk).
     */
    private fun startSystemSpeechToText(allowRetryOnDisconnect: Boolean = true) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Device speech recognition is not available.", Toast.LENGTH_LONG).show()
            return
        }
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Toast.makeText(this, "Mic permission required.", Toast.LENGTH_SHORT).show()
            permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val recognizer = speechRecognizer ?: run {
            Toast.makeText(this, "Cannot start device STT.", Toast.LENGTH_SHORT).show()
            return
        }

        // Start parallel mic capture so we can save audio and replay/test with Vosk later.
        startSystemSttRecording()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Prefer offline if the device has downloaded language packs (Samsung/Google)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "th-TH")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
        }

        setStatus("Listening with device STT...")
        systemSttButton.isEnabled = false

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    transcriptTextView.append("\n[Device STT] $text")
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    transcriptTextView.append("\n[Device STT] $text")
                    setStatus("Device STT: \"$text\"")
                } else {
                    setStatus("Device STT: no text")
                }
                systemSttButton.isEnabled = true
                stopSystemSttRecording(saveAudio = true)
            }

            override fun onError(error: Int) {
                Log.w("DashboardActivity", "Device STT error: $error")
                if (allowRetryOnDisconnect && error == DEVICE_STT_ERROR_SERVER_DISCONNECTED) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        // Recreate recognizer and retry once to avoid the first-press failure.
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        setStatus("Reconnecting device STT...")
                        delay(200)
                        startSystemSpeechToText(allowRetryOnDisconnect = false)
                    }
                    return
                }
                Toast.makeText(this@DashboardActivity, "Device STT error: $error", Toast.LENGTH_SHORT).show()
                setStatus("Idle")
                systemSttButton.isEnabled = true
                stopSystemSttRecording(saveAudio = true)
            }
        })

        recognizer.startListening(intent)
    }

    companion object {
        private const val DEVICE_STT_ERROR_SERVER_DISCONNECTED = 11
    }

    private fun startSystemSttRecording() {
        stopSystemSttRecording(saveAudio = false)
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            Log.w("DashboardActivity", "System STT mic start failed: ${e.message}", e)
            recorder.release()
            return
        }
        systemSttRecord = recorder
        systemSttBuffer = ByteArrayOutputStream()
        isSystemSttRecording = true
        systemSttRecordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            while (isActive && isSystemSttRecording) {
                val read = try { recorder.read(buffer, 0, buffer.size) } catch (_: Exception) { 0 }
                if (read > 0) {
                    systemSttBuffer?.write(buffer, 0, read)
                }
            }
        }
    }

    private fun stopSystemSttRecording(saveAudio: Boolean) {
        isSystemSttRecording = false
        systemSttRecordingJob?.cancel()
        systemSttRecordingJob = null
        try { systemSttRecord?.stop() } catch (_: Exception) { }
        systemSttRecord?.release()
        systemSttRecord = null

        val data = systemSttBuffer?.toByteArray()
        systemSttBuffer = null
        if (!saveAudio || data == null || data.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val wav = WavUtil.pcmToWav(data, 16000, 1, 16)
            val file = File(cacheDir, "device_stt_${System.currentTimeMillis()}.wav")
            file.writeBytes(wav)
            lastRecordingPath = file.absolutePath
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DashboardActivity, "Saved device STT audio for Vosk replay.", Toast.LENGTH_SHORT).show()
                updatePlayButtonState()
            }
        }
    }
}

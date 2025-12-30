package com.sentinel.ai.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.provider.CallLog
import android.provider.Telephony
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
import com.sentinel.ai.ai.WhisperCppSttClient
import com.sentinel.ai.service.InternalAudioCaptureService
import com.sentinel.ai.utils.MicCaptureManager
import com.sentinel.ai.utils.NetworkUtils
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.Locale
import kotlin.math.sqrt
import kotlin.math.min
import kotlin.math.roundToInt
import org.vosk.Recognizer

open class DashboardActivity : AppCompatActivity() {

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
    private var hybridMic: MicCaptureManager? = null
    private var hybridChannel: Channel<ByteArray>? = null
    private var hybridJob: kotlinx.coroutines.Job? = null
    private var stableListenJob: kotlinx.coroutines.Job? = null
    private var stableRecorder: AudioRecord? = null
    private var stableRecognizer: Recognizer? = null
    @Volatile private var stableLastAudioMs: Long = 0
    @Volatile private var stableEmptyStreak: Int = 0
    private lateinit var callLogContainer: LinearLayout
    private lateinit var smsRiskContainer: LinearLayout

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    private val callSmsPermissions = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_SMS
    )

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) {
            continueStartCaptureProcess()
        } else {
            Toast.makeText(this, "Audio and Notification permissions are required.", Toast.LENGTH_LONG).show()
            setStatus("Idle")
        }
    }

    private val callSmsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshRiskPanels()
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
        callLogContainer = findViewById(R.id.callLogContainer)
        smsRiskContainer = findViewById(R.id.smsRiskContainer)

        startCaptureButton.setOnClickListener { startCaptureProcess() }
        stopCaptureButton.setOnClickListener { stopCaptureProcess() }
        playRecordingButton.setOnClickListener { playLastRecording() }
        transcribeRecordingButton.setOnClickListener { transcribeLastRecording() }
        testMicButton.setOnClickListener { runMicTest() }
        testMicButton.setOnLongClickListener {
            toggleHybridLiveStt()
            true
        }
        systemSttButton.setOnClickListener { startSystemSpeechToText() }
        clearTranscriptButton.setOnClickListener { transcriptTextView.text = "" }
        setStatus("Idle")
        updatePlayButtonState()
        refreshRiskPanels()
    }

    override fun onResume() {
        super.onResume()
        registerAppEventsReceiver()
        updateUiState()
        refreshRiskPanels()
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
            OfflineStt.resetRecognizer()
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

                val onlineStart = System.currentTimeMillis()
                val onlineTranscript = if (shouldUseOnlineStt()) {
                    transcribeOnlineInChunks(
                        pcm = pcm,
                        sampleRate = sampleRate,
                        channels = channels
                    )
                } else null
                if (!onlineTranscript.isNullOrBlank()) {
                    Log.d("DashboardActivity", "Online file STT ok in ${System.currentTimeMillis() - onlineStart}ms len=${onlineTranscript.length}")
                } else {
                    Log.d("DashboardActivity", "Online file STT empty/null after ${System.currentTimeMillis() - onlineStart}ms, using offline fallback")
                }

                val offlineStart = System.currentTimeMillis()
                val offlineTranscript = OfflineStt.transcribePcm16(
                    context = this@DashboardActivity,
                    audio = pcm,
                    sampleRate = sampleRate,
                    isStereo = channels >= 2
                ).orEmpty()
                if (offlineTranscript.isNotBlank()) {
                    Log.d("DashboardActivity", "Offline file STT ok in ${System.currentTimeMillis() - offlineStart}ms len=${offlineTranscript.length}")
                }

                val transcript = onlineTranscript?.takeIf { it.isNotBlank() } ?: offlineTranscript
                val sourceTag = if (!onlineTranscript.isNullOrBlank()) "Online File STT" else "File STT"

                withContext(Dispatchers.Main) {
                    if (transcript.isNotBlank()) {
                        transcriptTextView.append("\n[$sourceTag] $transcript")
                        setStatus("$sourceTag: \"$transcript\"")
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
        stopHybridLiveStt()
        stopLiveOfflineStt()
        stopSystemSttRecording(saveAudio = false)
        super.onDestroy()
    }

    private fun toggleHybridLiveStt() {
        if (hybridJob?.isActive == true) {
            stopHybridLiveStt()
        } else {
            startHybridLiveStt()
        }
    }

    private fun startHybridLiveStt() {
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
        // Ensure offline model is ready for fallback
        if (!OfflineStt.ensureModel(this)) {
            Toast.makeText(this, "Offline model missing or failed to load. Check assets/models/vosk-model.zip.", Toast.LENGTH_LONG).show()
            setStatus("Live STT: model missing")
            return
        }
        stopLiveOfflineStt()
        stopHybridLiveStt()

        val channel = Channel<ByteArray>(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        hybridChannel = channel
        hybridJob = lifecycleScope.launch(Dispatchers.IO) {
            for (chunk in channel) {
                val (text, source) = try {
                    transcribeHybridChunk(chunk, HYBRID_SAMPLE_RATE)
                } catch (e: Exception) {
                    Log.w("DashboardActivity", "Hybrid STT chunk failed: ${e.message}", e)
                    Pair<String?, String>("", "")
                }
                if (!text.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        val tag = if (source.isNotBlank()) source else "Live STT"
                        transcriptTextView.append("\n[$tag] $text")
                        setStatus("$tag: \"$text\"")
                    }
                }
            }
        }

        val mic = MicCaptureManager(this)
        hybridMic = mic
        mic.start(
            onChunk = { chunk -> hybridChannel?.trySend(chunk) },
            onError = { msg ->
                lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@DashboardActivity, "Mic error: $msg", Toast.LENGTH_SHORT).show()
                    stopHybridLiveStt()
                }
            },
            chunkMs = 1000 // send smaller chunks so online STT fires faster
        )

        testMicButton.text = "Stop Live STT"
        setStatus("Live STT (online preferred)")
    }

    private fun stopHybridLiveStt() {
        hybridMic?.stop()
        hybridMic = null
        try { hybridChannel?.close() } catch (_: Exception) { }
        hybridChannel = null
        hybridJob?.cancel()
        hybridJob = null
        testMicButton.text = "Test Mic (Hybrid STT)"
        if (!InternalAudioCaptureService.isServiceRunning) setStatus("Idle")
    }

    private suspend fun transcribeHybridChunk(chunk: ByteArray, sampleRate: Int): Pair<String?, String> {
        var attemptedOnline = false
        if (shouldUseOnlineStt()) {
            attemptedOnline = true
            WhisperCppSttClient.transcribePcm16(chunk, sampleRate, 1)?.let {
                return it to "Online STT"
            }
        }
        val offline = OfflineStt.transcribePcm16(
            context = this,
            audio = chunk,
            sampleRate = sampleRate,
            isStereo = false
        )
        val source = if (attemptedOnline) "Offline fallback" else "Offline STT"
        return offline to source
    }

    /**
     * Send a recorded file to online STT in multiple smaller requests to reduce timeout risk
     * and to allow the server to return text incrementally.
     */
    private fun transcribeOnlineInChunks(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        chunkMs: Int = ONLINE_FILE_CHUNK_MS
    ): String? {
        if (pcm.isEmpty()) return null
        val bytesPerMs = (sampleRate * channels * 2) / 1000 // 16-bit PCM -> 2 bytes per sample
        if (bytesPerMs <= 0) return null
        val sb = StringBuilder()
        var offset = 0
        var idx = 0
        while (offset < pcm.size) {
            val end = min(pcm.size, offset + bytesPerMs * chunkMs)
            val chunk = pcm.copyOfRange(offset, end)
            val chunkStart = System.currentTimeMillis()
            Log.d("DashboardActivity", "Online chunk ${++idx}: bytes=${chunk.size} sr=$sampleRate ch=$channels start=$chunkStart")
            WhisperCppSttClient.transcribePcm16(chunk, sampleRate, channels)?.let { piece ->
                Log.d("DashboardActivity", "Online chunk $idx done in ${System.currentTimeMillis() - chunkStart}ms text='${piece.take(40)}'")
                if (piece.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(piece.trim())
                }
            } ?: run {
                Log.d("DashboardActivity", "Online chunk $idx done in ${System.currentTimeMillis() - chunkStart}ms text=null")
            }
            offset = end
        }
        return sb.toString().ifBlank { null }
    }

    private fun shouldUseOnlineStt(): Boolean {
        return WhisperCppSttClient.isConfigured() && NetworkUtils.isOnline(this)
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
        OfflineStt.resetRecognizer()
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
                OfflineStt.resetRecognizer()
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
        return sqrt(mean.toDouble()).toInt()
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

    private fun refreshRiskPanels() {
        if (!hasCallSmsPermissions()) {
            renderPermissionCta(
                container = callLogContainer,
                message = "ต้องการสิทธิ์อ่านประวัติการโทรเพื่อแสดงรายการล่าสุด"
            )
            renderPermissionCta(
                container = smsRiskContainer,
                message = "ต้องการสิทธิ์อ่าน SMS เพื่อสแกนข้อความเสี่ยง"
            )
            return
        }
        loadCallLog()
        loadSmsRisks()
    }

    private fun hasCallSmsPermissions(): Boolean = callSmsPermissions.all { hasPermission(it) }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun renderPermissionCta(container: LinearLayout, message: String) {
        container.removeAllViews()
        val info = buildInfoText(message)
        val action = Button(this).apply {
            text = "อนุญาตตอนนี้"
            setOnClickListener { callSmsPermissionLauncher.launch(callSmsPermissions) }
        }
        container.addView(info)
        container.addView(action)
    }

    private fun loadCallLog(maxItems: Int = 10) {
        callLogContainer.removeAllViews()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME
        )
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < maxItems) {
                    val number = cursor.getString(0).orEmpty()
                    val type = cursor.getInt(1)
                    val date = cursor.getLong(2)
                    val duration = cursor.getLong(3)
                    val name = cursor.getString(4)
                    val entry = CallLogEntry(
                        name = name,
                        number = number,
                        type = type,
                        durationSec = duration,
                        timestamp = date
                    )
                    val (riskLabel, riskColor) = computeCallRisk(entry)
                    addCallRow(entry, riskLabel, riskColor)
                    count++
                }
            }
        } catch (e: SecurityException) {
            renderPermissionCta(
                container = callLogContainer,
                message = "ไม่สามารถอ่านประวัติการโทร: ${e.message}"
            )
            return
        }
        if (callLogContainer.childCount == 0) {
            callLogContainer.addView(buildInfoText("ไม่พบประวัติการโทรล่าสุด"))
        }
    }

    private fun addCallRow(entry: CallLogEntry, riskLabel: String, riskColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = buildRowBackground()
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(8), 0, 0)
            layoutParams = lp
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val title = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = entry.displayName
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 15f
        }
        val badge = createBadge(riskLabel, riskColor)
        header.addView(title)
        header.addView(badge)

        val meta = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = "${formatCallType(entry.type)} • ${formatDuration(entry.durationSec)} • ${
                DateUtils.getRelativeTimeSpanString(
                    entry.timestamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
            }"
            setTextColor(Color.parseColor("#A4B4C8"))
            textSize = 13f
        }

        row.addView(header)
        row.addView(meta)
        callLogContainer.addView(row)
    }

    private fun computeCallRisk(entry: CallLogEntry): Pair<String, Int> {
        var score = 0
        val unknownCaller = entry.name.isNullOrBlank() && entry.number.isBlank()
        if (unknownCaller) score += 1
        if (entry.durationSec in 1..10 && entry.type == CallLog.Calls.INCOMING_TYPE) score += 1
        if (entry.type == CallLog.Calls.MISSED_TYPE && entry.durationSec == 0L) {
            return "ไม่ได้วัด (สายไม่ได้รับ)" to Color.parseColor("#8AA0B5")
        }
        return when {
            score >= 2 -> "สูง (สายไม่รู้จัก/คุยสั้น)" to Color.parseColor("#FF6B6B")
            score == 1 -> "กลาง (ต้องตรวจสอบ)" to Color.parseColor("#FFC857")
            else -> "ไม่ได้วัด (ข้อมูลไม่พอ)" to Color.parseColor("#8AA0B5")
        }
    }

    private fun formatCallType(type: Int): String = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> "โทรออก"
        CallLog.Calls.INCOMING_TYPE -> "โทรเข้า"
        CallLog.Calls.MISSED_TYPE -> "สายไม่ได้รับ"
        CallLog.Calls.REJECTED_TYPE -> "ปฏิเสธสาย"
        else -> "ไม่ทราบชนิด"
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val minutes = seconds / 60
        val sec = seconds % 60
        return if (minutes > 0) "${minutes}m ${sec}s" else "${sec}s"
    }

    private fun loadSmsRisks(maxItems: Int = 40) {
        smsRiskContainer.removeAllViews()
        var checked = 0
        var flagged = 0
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        try {
            contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (cursor.moveToNext() && checked < maxItems) {
                    val address = cursor.getString(addressIdx).orEmpty()
                    val body = cursor.getString(bodyIdx).orEmpty()
                    val date = cursor.getLong(dateIdx)
                    val score = scoreSms(body)
                    if (score >= 2) {
                        val (label, color) = smsRiskLabel(score)
                        addSmsRow(address, body, date, label, color)
                        flagged++
                    }
                    checked++
                }
            }
        } catch (e: SecurityException) {
            renderPermissionCta(
                container = smsRiskContainer,
                message = "ไม่สามารถอ่าน SMS: ${e.message}"
            )
            return
        }

        if (flagged == 0) {
            smsRiskContainer.addView(buildInfoText("ไม่พบข้อความเสี่ยงใน $maxItems ข้อความล่าสุด"))
        }
    }

    private fun scoreSms(body: String): Int {
        val lower = body.lowercase(Locale.getDefault())
        var score = 0
        val highRiskKeywords = listOf(
            "otp",
            "one time password",
            "transfer",
            "โอน",
            "ระงับ",
            "เร่งด่วน",
            "ยืนยันตัวตน",
            "รีบทำ",
            "ลิงก์",
            "คลิกลิงก์",
            "police",
            "lawsuit",
            "freeze",
            "บัญชีถูกปิด"
        )
        if (highRiskKeywords.any { lower.contains(it) }) score += 2
        if (lower.contains("http://") || lower.contains("https://") || lower.contains("bit.ly") || lower.contains("tinyurl")) score += 2
        if (Regex("\\b\\d{6}\\b").containsMatchIn(lower) && lower.contains("otp")) score += 1
        if (lower.count { it == '!' } >= 2) score += 1
        return score
    }

    private fun smsRiskLabel(score: Int): Pair<String, Int> = when {
        score >= 4 -> "สูง" to Color.parseColor("#FF6B6B")
        score >= 2 -> "กลาง" to Color.parseColor("#FFC857")
        else -> "ต่ำ" to Color.parseColor("#6DD3A6")
    }

    private fun addSmsRow(address: String, body: String, date: Long, riskLabel: String, riskColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = buildRowBackground()
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(8), 0, 0)
            layoutParams = lp
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val from = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = address.ifBlank { "ผู้ส่งไม่ระบุ" }
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 15f
        }
        val badge = createBadge("SMS $riskLabel", riskColor)
        header.addView(from)
        header.addView(badge)

        val snippet = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = body.take(140).trim().ifBlank { "(ข้อความว่าง)" }
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
        }

        val meta = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = DateUtils.getRelativeTimeSpanString(
                date,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            setTextColor(Color.parseColor("#A4B4C8"))
            textSize = 12f
        }

        row.addView(header)
        row.addView(snippet)
        row.addView(meta)
        smsRiskContainer.addView(row)
    }

    private fun buildRowBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor("#141a24"))
        setStroke(1, Color.parseColor("#223956"))
    }

    private fun createBadge(text: String, color: Int): TextView = TextView(this).apply {
        val badgePadding = dp(8)
        setPadding(badgePadding, dp(4), badgePadding, dp(4))
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        textSize = 12f
        this.text = text
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color)
        }
    }

    private fun buildInfoText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#B3FFFFFF"))
        textSize = 13f
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private data class CallLogEntry(
        val name: String?,
        val number: String,
        val type: Int,
        val durationSec: Long,
        val timestamp: Long
    ) {
        val displayName: String
            get() = when {
                !name.isNullOrBlank() && number.isNotBlank() -> "$name • $number"
                !name.isNullOrBlank() -> name
                number.isNotBlank() -> number
                else -> "ไม่ระบุผู้โทร"
            }
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
        private const val HYBRID_SAMPLE_RATE = 16000
        private const val ONLINE_FILE_CHUNK_MS = 3000 // ~3s per request to reduce timeout risk
        private const val STABLE_SAMPLE_RATE = 16000
        private const val STABLE_NOISE_GATE = 500
        private const val STABLE_WATCHDOG_MS = 5000L
        private const val STABLE_EMPTY_MAX = 10
    }

    private fun startSystemSttRecording() {
        stopSystemSttRecording(saveAudio = false)
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Toast.makeText(this, "Mic permission required to save device audio.", Toast.LENGTH_SHORT).show()
            permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
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
        } catch (e: SecurityException) {
            Log.w("DashboardActivity", "System STT mic permission denied: ${e.message}", e)
            recorder.release()
            Toast.makeText(this, "Mic permission denied for device audio capture.", Toast.LENGTH_SHORT).show()
            return
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

    /**
     * Stable offline listening loop with noise gate, watchdog, and recognizer restart.
     * Call stableStartListening() to begin, stableStopListening() to end.
     */
    fun stableStartListening() {
        if (stableListenJob?.isActive == true) return
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Toast.makeText(this, "Mic permission required.", Toast.LENGTH_SHORT).show()
            permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (!OfflineStt.ensureModel(this)) {
            Toast.makeText(this, "Offline model missing. Check assets/models/vosk-model.zip.", Toast.LENGTH_LONG).show()
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            STABLE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBuf * 2).coerceAtLeast(4096)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            STABLE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        val recognizer = OfflineStt.createRecognizer(this, STABLE_SAMPLE_RATE)
        if (recognizer == null) {
            Toast.makeText(this, "Cannot create Vosk recognizer.", Toast.LENGTH_SHORT).show()
            recorder.release()
            return
        }

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            Toast.makeText(this, "Mic start failed: ${e.message}", Toast.LENGTH_LONG).show()
            recorder.release()
            recognizer.close()
            return
        }

        stableRecorder = recorder
        stableRecognizer = recognizer
        stableLastAudioMs = System.currentTimeMillis()
        stableEmptyStreak = 0

        stableListenJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            while (isActive) {
                val read = try { recorder.read(buffer, 0, buffer.size) } catch (_: Exception) { 0 }
                if (read <= 0) continue

                val rms = computeRms(buffer.copyOf(read))
                val now = System.currentTimeMillis()
                if (rms < STABLE_NOISE_GATE) {
                    if (now - stableLastAudioMs > STABLE_WATCHDOG_MS) {
                        Log.d("DashboardActivity", "Watchdog: no audio, restarting recognizer")
                        restartStableRecognizer()
                        stableLastAudioMs = now
                    }
                    continue
                }

                stableLastAudioMs = now
                try {
                    val ok = recognizer.acceptWaveForm(buffer, read)
                    val json = if (ok) recognizer.result else recognizer.partialResult
                    val text = parseVoskText(json)
                    if (!text.isNullOrBlank()) {
                        stableEmptyStreak = 0
                        withContext(Dispatchers.Main) {
                            transcriptTextView.append("\n[Stable Vosk] $text")
                            setStatus("Listening: $text")
                        }
                    } else {
                        stableEmptyStreak++
                        if (stableEmptyStreak >= STABLE_EMPTY_MAX) {
                            Log.d("DashboardActivity", "Empty streak reached, restarting recognizer")
                            restartStableRecognizer()
                            stableEmptyStreak = 0
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DashboardActivity", "Stable recognizer error: ${e.message}", e)
                    restartStableRecognizer()
                }
            }
        }
    }

    fun stableStopListening() {
        stableListenJob?.cancel()
        stableListenJob = null
        try { stableRecorder?.stop() } catch (_: Exception) { }
        stableRecorder?.release()
        stableRecorder = null
        try { stableRecognizer?.close() } catch (_: Exception) { }
        stableRecognizer = null
        stableEmptyStreak = 0
        setStatus("Idle")
    }

    private fun restartStableRecognizer() {
        try { stableRecognizer?.close() } catch (_: Exception) { }
        stableRecognizer = OfflineStt.createRecognizer(this, STABLE_SAMPLE_RATE)
    }
}

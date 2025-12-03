package com.sentinel.ai.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Build
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.sentinel.ai.R
import com.sentinel.ai.ai.RiskScoring
import com.sentinel.ai.ai.WhisperEngine
import com.sentinel.ai.databinding.ActivityDashboardBinding
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.service.SentinelGuardianService
import com.sentinel.ai.utils.MicCaptureManager
import com.sentinel.ai.utils.OverlayController
import com.sentinel.ai.utils.PlaybackCaptureController
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.SpeechTestController

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val adapter = EventsAdapter()
    private val viewModel: DashboardViewModel by viewModels()
    private lateinit var speechTester: SpeechTestController
    private val whisperFallback = WhisperEngine()
    private val riskScoring = RiskScoring()
    private val micCapture by lazy { MicCaptureManager(this) }
    private var playbackCapture: PlaybackCaptureController? = null
    private var mediaProjection: MediaProjection? = null
    private var pendingStartInternal = false
    private lateinit var overlayController: OverlayController
    private var aggressiveListening = false
    private var loadingDismissed = false
    private var listeningDialog: AlertDialog? = null
    private var listeningTextView: android.widget.TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SentinelGuardianService.start(this)
        speechTester = SpeechTestController(this)
        overlayController = OverlayController(this)

        binding.recentRecycler.layoutManager = LinearLayoutManager(this)
        binding.recentRecycler.adapter = adapter

        binding.guardianToggle.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setGuardianEnabled(isChecked)
        }

        binding.btnTestStt.setOnClickListener { startMicTest() }
        binding.btnMockChat.setOnClickListener { viewModel.runMockChat() }
        binding.btnMockCall.setOnClickListener { viewModel.runMockCall() }
        binding.btnClearEvents.setOnClickListener { viewModel.clearEvents() }
        binding.btnAggressiveListen.setOnClickListener { startAggressiveMic() }
        binding.btnAggressiveStop.setOnClickListener { stopAggressiveMic() }

        viewModel.guardianEnabled.observe(this) { enabled ->
            binding.guardianToggle.isChecked = enabled
        }
        viewModel.status.observe(this) { level -> renderStatus(level) }
        viewModel.events.observe(this) { events ->
            adapter.submit(events)
            renderEventSummary(events)
            if (!loadingDismissed) {
                loadingDismissed = true
                binding.loadingOverlay.isVisible = false
            }
        }

        // Fallback hide loader after 2 seconds even if no events yet.
        binding.root.postDelayed({
            if (!loadingDismissed) {
                loadingDismissed = true
                binding.loadingOverlay.isVisible = false
            }
        }, 2000)
    }

    private fun startMicTest() {
        if (!PermissionUtils.hasMicPermission(this)) {
            PermissionUtils.requestMicPermission(this, REQ_MIC_STT)
            return
        }
        binding.tvLiveTranscript.text = "Listening..."
        speechTester.listenOnce(
            onResult = { text ->
                runOnUiThread {
                    binding.tvLiveTranscript.text = "Final: $text"
                    viewModel.handleTranscript(text)
                }
            },
            onError = { err ->
                val fallback = whisperFallback.transcribe()
                runOnUiThread {
                    binding.tvLiveTranscript.text = "Error: $err\nFallback: $fallback"
                    viewModel.handleTranscript(fallback)
                }
            },
            onPartial = { partial ->
                runOnUiThread { binding.tvLiveTranscript.text = "Heard: $partial" }
            },
            languageTag = PREFERRED_LANGS
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC_STT && PermissionUtils.hasMicPermission(this)) {
            startMicTest()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mgr.getMediaProjection(resultCode, data)
                if (pendingStartInternal) {
                    pendingStartInternal = false
                    startAggressiveMic(usePlayback = true)
                }
            } else {
                pendingStartInternal = false
                aggressiveListening = false
                startAggressiveMic(usePlayback = false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechTester.destroy()
        micCapture.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            playbackCapture?.destroy()
            mediaProjection?.stop()
        }
    }

    private fun renderEventSummary(events: List<com.sentinel.ai.model.GuardianEvent>) {
        val critical = events.count { it.riskLevel == RiskLevel.CRITICAL }
        val warning = events.count { it.riskLevel == RiskLevel.WARNING }
        val safe = events.count { it.riskLevel == RiskLevel.SAFE }
        binding.tvEventCounts?.text = "Critical $critical | Warning $warning | Safe $safe"
        val latest = events.firstOrNull()
        binding.tvLastEvent?.text = latest?.let {
            "Last: ${it.source} (${it.riskLevel.name}, score ${it.score})"
        } ?: "Last: none yet"
    }

    private fun renderStatus(level: RiskLevel) {
        val text = when (level) {
            RiskLevel.SAFE -> getString(R.string.status_monitoring)
            RiskLevel.WARNING -> getString(R.string.status_warning)
            RiskLevel.CRITICAL -> getString(R.string.status_critical)
        }
        val color = when (level) {
            RiskLevel.SAFE -> R.color.sentinel_on_surface
            RiskLevel.WARNING -> R.color.sentinel_warning
            RiskLevel.CRITICAL -> R.color.sentinel_critical
        }
        binding.statusValue.text = text
        binding.statusValue.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun startAggressiveMic(usePlayback: Boolean = true) {
        if (!PermissionUtils.hasMicPermission(this)) {
            PermissionUtils.requestMicPermission(this, REQ_MIC_STT)
            return
        }
        if (aggressiveListening) return
        aggressiveListening = true
        binding.tvLiveTranscript.text = "Aggressive monitor: mic listening..."
        ensureLiveOverlayVisible("Mic listening... capturing internal audio when allowed.")
        if (usePlayback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = mediaProjection
            if (projection == null) {
                pendingStartInternal = true
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
                return
            } else {
                startPlaybackCapture(projection)
            }
        } else {
            startMicContinuous()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun startPlaybackCapture(projection: MediaProjection) {
        playbackCapture?.stop()
        playbackCapture = PlaybackCaptureController(projection)
        ensureLiveOverlayVisible("Capturing screen audio... generating captions.")
        try {
            playbackCapture?.start(
                onChunk = { bytes ->
                    val transcript = whisperFallback.transcribe(bytes)
                    runOnUiThread {
                        val display = transcript.ifBlank { "Audio playing..." }
                        binding.tvLiveTranscript.text = display
                        updateListeningUi(display)
                        if (transcript.isNotBlank()) {
                            viewModel.addRawTranscript(transcript)
                        }
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        binding.tvLiveTranscript.text = "Playback capture error: $err (fallback to mic)"
                        startMicContinuous()
                    }
                }
            )
        } catch (e: Exception) {
            runOnUiThread {
                binding.tvLiveTranscript.text = "Playback capture failed: ${e.message ?: "unknown"} (fallback to mic)"
                startMicContinuous()
            }
        }
    }

    private fun startMicContinuous() {
        ensureLiveOverlayVisible("Mic listening for live captions...")
        speechTester.listenContinuously(
            onResult = { text ->
                runOnUiThread {
                    binding.tvLiveTranscript.text = text
                    updateListeningUi(text)
                    viewModel.addRawTranscript(text)
                }
            },
            onError = { err ->
                runOnUiThread {
                    binding.tvLiveTranscript.text = "STT error (auto-retrying): $err"
                    updateListeningUi("STT error: $err")
                }
            },
            onPartial = { partial ->
                if (partial.isNotBlank() && partial != "...") {
                    runOnUiThread {
                        updateListeningUi(partial)
                    }
                }
            },
            languageTag = PREFERRED_LANGS
        )
    }

    private fun stopAggressiveMic() {
        if (!aggressiveListening) return
        aggressiveListening = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            playbackCapture?.stop()
        }
        speechTester.stopContinuous()
        binding.tvLiveTranscript.text = "Aggressive monitor stopped."
        overlayController.dismiss()
        listeningDialog?.dismiss()
        listeningDialog = null
        listeningTextView = null
    }

    private fun ensureLiveOverlayVisible(initialText: String) {
        if (PermissionUtils.canDrawOverlays(this)) {
            overlayController.showLiveTranscript(initialText)
        } else {
            showListeningPopup(initialText)
        }
    }

    private fun updateListeningUi(text: String) {
        if (PermissionUtils.canDrawOverlays(this)) {
            overlayController.updateLiveTranscript(text)
        } else {
            listeningTextView?.text = text
        }
    }

    private fun showListeningPopup(initialText: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_listening_overlay, null)
        listeningTextView = dialogView.findViewById(R.id.tvListeningText)
        listeningTextView?.text = initialText
        listeningDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("Stop") { d, _ ->
                stopAggressiveMic()
                d.dismiss()
            }
            .show()
    }

    companion object {
        private const val REQ_MIC_STT = 501
        private const val REQ_MEDIA_PROJECTION = 502
        private const val PREFERRED_LANGS = "th-TH"
    }
}

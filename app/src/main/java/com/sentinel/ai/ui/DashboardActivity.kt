package com.sentinel.ai.ui

import android.os.Bundle
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

    override fun onDestroy() {
        super.onDestroy()
        speechTester.destroy()
        micCapture.stop()
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

    private fun startAggressiveMic() {
        if (!PermissionUtils.hasMicPermission(this)) {
            PermissionUtils.requestMicPermission(this, REQ_MIC_STT)
            return
        }
        if (aggressiveListening) return
        aggressiveListening = true
        binding.tvLiveTranscript.text = "Aggressive monitor: mic listening..."
        if (PermissionUtils.canDrawOverlays(this)) {
            overlayController.showLiveTranscript("Mic listening... hold near the speaker if playing a clip.")
        } else {
            showListeningPopup("Mic listening... hold near the speaker if playing a clip.")
        }
        micCapture.start(
            onChunk = { bytes ->
                val transcript = whisperFallback.transcribe(bytes)
                val behavior = com.sentinel.ai.utils.PressureAnalyzer().analyze(transcript)
                val risk = riskScoring.score(transcript, behavior)
                val level = when {
                    risk.score >= 80 -> RiskLevel.CRITICAL
                    risk.score in 40..79 -> RiskLevel.WARNING
                    else -> RiskLevel.SAFE
                }
                runOnUiThread {
                    binding.tvLiveTranscript.text = "Aggressive final: $transcript"
                    if (PermissionUtils.canDrawOverlays(this)) {
                        overlayController.updateLiveTranscript(transcript)
                        overlayController.updateLiveTranscriptRisk(level)
                    } else {
                        listeningTextView?.text = transcript
                    }
                    viewModel.handleTranscript(transcript)
                }
            },
            onError = { err ->
                runOnUiThread {
                    binding.tvLiveTranscript.text = "Mic capture error (auto-retrying): $err"
                    if (PermissionUtils.canDrawOverlays(this)) {
                        overlayController.updateLiveTranscript("Mic capture error: $err")
                        overlayController.updateLiveTranscriptRisk(RiskLevel.SAFE)
                    } else {
                        listeningTextView?.text = "Mic capture error: $err"
                    }
                }
            }
        )
    }

    private fun stopAggressiveMic() {
        if (!aggressiveListening) return
        aggressiveListening = false
        micCapture.stop()
        binding.tvLiveTranscript.text = "Aggressive monitor stopped."
        overlayController.dismiss()
        listeningDialog?.dismiss()
        listeningDialog = null
        listeningTextView = null
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
        private const val PREFERRED_LANGS = "th-TH"
    }
}

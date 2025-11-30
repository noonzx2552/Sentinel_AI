package com.sentinel.ai.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.sentinel.ai.R
import com.sentinel.ai.ai.WhisperEngine
import com.sentinel.ai.databinding.ActivityDashboardBinding
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.service.SentinelGuardianService
import com.sentinel.ai.utils.OverlayController
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.SpeechTestController

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val adapter = EventsAdapter()
    private val viewModel: DashboardViewModel by viewModels()
    private lateinit var speechTester: SpeechTestController
    private val whisperFallback = WhisperEngine()
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
        binding.tvLiveTranscript.text = "Aggressive monitor: listening..."
        if (PermissionUtils.canDrawOverlays(this)) {
            overlayController.showLiveTranscript("กำลังฟังเสียงจากเครื่อง...")
        } else {
            showListeningPopup("กำลังฟังเสียงจากเครื่อง...")
        }
        speechTester.listenContinuously(
            onResult = { text ->
                runOnUiThread {
                    binding.tvLiveTranscript.text = "Aggressive final: $text"
                    if (PermissionUtils.canDrawOverlays(this)) {
                        overlayController.updateLiveTranscript(text)
                    } else {
                        listeningTextView?.text = text
                    }
                    viewModel.handleTranscript(text)
                }
            },
            onError = { err ->
                runOnUiThread {
                    binding.tvLiveTranscript.text = "Aggressive listening… (auto-retrying)"
                    if (PermissionUtils.canDrawOverlays(this)) {
                        overlayController.updateLiveTranscript("กำลังฟังต่อ... ($err)")
                    } else {
                        listeningTextView?.text = "กำลังฟังต่อ... ($err)"
                    }
                }
            },
            onPartial = { partial ->
                if (partial != "...") {
                    runOnUiThread {
                        binding.tvLiveTranscript.text = "Aggressive heard: $partial"
                        if (PermissionUtils.canDrawOverlays(this)) {
                            overlayController.updateLiveTranscript(partial)
                        } else {
                            listeningTextView?.text = partial
                        }
                    }
                }
            },
            languageTag = PREFERRED_LANGS
        )
    }

    private fun stopAggressiveMic() {
        if (!aggressiveListening) return
        aggressiveListening = false
        speechTester.stopContinuous()
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
            .setNegativeButton("ปิด") { d, _ ->
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

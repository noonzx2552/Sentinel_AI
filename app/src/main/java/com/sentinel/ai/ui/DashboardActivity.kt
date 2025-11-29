package com.sentinel.ai.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.sentinel.ai.R
import com.sentinel.ai.databinding.ActivityDashboardBinding
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.service.SentinelGuardianService
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.SpeechTestController
import com.sentinel.ai.ai.WhisperEngine

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val adapter = EventsAdapter()
    private val viewModel: DashboardViewModel by viewModels()
    private lateinit var speechTester: SpeechTestController
    private val whisperFallback = WhisperEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SentinelGuardianService.start(this)
        speechTester = SpeechTestController(this)

        binding.recentRecycler.layoutManager = LinearLayoutManager(this)
        binding.recentRecycler.adapter = adapter

        binding.guardianToggle.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setGuardianEnabled(isChecked)
        }

        binding.btnTestStt.setOnClickListener {
            startMicTest()
        }
        binding.btnMockChat.setOnClickListener {
            viewModel.runMockChat()
        }
        binding.btnMockCall.setOnClickListener {
            viewModel.runMockCall()
        }
        binding.btnClearEvents.setOnClickListener {
            viewModel.clearEvents()
        }

        viewModel.guardianEnabled.observe(this) { enabled ->
            binding.guardianToggle.isChecked = enabled
        }

        viewModel.status.observe(this) { level ->
            renderStatus(level)
        }

        viewModel.events.observe(this) { events ->
            adapter.submit(events)
        }
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

    companion object {
        private const val REQ_MIC_STT = 501
        private const val PREFERRED_LANGS = "th-TH"
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
}

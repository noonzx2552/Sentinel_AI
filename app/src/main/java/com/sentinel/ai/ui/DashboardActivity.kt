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

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val adapter = EventsAdapter()
    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SentinelGuardianService.start(this)

        binding.recentRecycler.layoutManager = LinearLayoutManager(this)
        binding.recentRecycler.adapter = adapter

        binding.guardianToggle.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setGuardianEnabled(isChecked)
        }

        binding.btnTestStt.setOnClickListener {
            viewModel.runSttTest()
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

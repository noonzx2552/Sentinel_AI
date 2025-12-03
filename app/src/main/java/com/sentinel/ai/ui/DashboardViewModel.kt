package com.sentinel.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.sentinel.ai.ai.RiskScoring
import com.sentinel.ai.ai.WhisperEngine
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.service.SentinelGuardianService
import com.sentinel.ai.utils.PressureAnalyzer

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    val events: LiveData<List<GuardianEvent>> = GuardianEventStore.observeEvents()
    val status: LiveData<RiskLevel> = GuardianEventStore.observeStatus()

    private val _guardianEnabled = MutableLiveData(true)
    val guardianEnabled: LiveData<Boolean> = _guardianEnabled

    private val whisper = WhisperEngine()
    private val riskScoring = RiskScoring()
    private val pressureAnalyzer = PressureAnalyzer()

    private fun levelFromScore(score: Int): RiskLevel = when {
        score >= 80 -> RiskLevel.CRITICAL
        score in 40..79 -> RiskLevel.WARNING
        else -> RiskLevel.SAFE
    }

    fun setGuardianEnabled(enabled: Boolean) {
        _guardianEnabled.value = enabled
        val context = getApplication<Application>()
        if (enabled) {
            SentinelGuardianService.start(context)
        } else {
            SentinelGuardianService.stop(context)
        }
    }

    fun runSttTest() {
        val transcript = whisper.transcribe()
        val behavior = pressureAnalyzer.analyze(transcript)
        val risk = riskScoring.score(transcript, behavior)
        val level = levelFromScore(risk.score)
        GuardianEventStore.addEvent(
            GuardianEvent(
                source = "STT Test",
                content = transcript,
                score = risk.score,
                riskLevel = level
            )
        )
    }

    fun addRawTranscript(text: String) {
        GuardianEventStore.addEvent(
            GuardianEvent(
                source = "Mic STT",
                content = text,
                score = 0,
                riskLevel = RiskLevel.SAFE
            )
        )
    }

    fun handleTranscript(text: String) {
        val behavior = pressureAnalyzer.analyze(text)
        val risk = riskScoring.score(text, behavior)
        GuardianEventStore.addEvent(
            GuardianEvent(
                source = "Mic STT",
                content = text,
                score = risk.score,
                riskLevel = levelFromScore(risk.score)
            )
        )
    }

    fun runMockChat() {
        val text = "urgent please verify your account and send otp now"
        val risk = riskScoring.score(text, RiskScoring.BehaviorFlags(pressureDetected = true))
        GuardianEventStore.addEvent(
            GuardianEvent(
                source = "Chat Mock",
                content = text,
                score = risk.score,
                riskLevel = levelFromScore(risk.score)
            )
        )
    }

    fun runMockCall() {
        val text = "This is an officer. Transfer funds immediately to avoid arrest."
        val risk = riskScoring.score(text, RiskScoring.BehaviorFlags(pressureDetected = true, logicConflict = true))
        GuardianEventStore.addEvent(
            GuardianEvent(
                source = "Call Mock",
                content = text,
                score = risk.score,
                riskLevel = levelFromScore(risk.score)
            )
        )
    }

    fun clearEvents() {
        GuardianEventStore.clear()
    }
}

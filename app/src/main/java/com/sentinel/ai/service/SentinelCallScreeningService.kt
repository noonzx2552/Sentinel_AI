package com.sentinel.ai.service

import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import com.sentinel.ai.ai.RiskScoring
import com.sentinel.ai.ai.WhisperEngine
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.utils.OverlayController
import com.sentinel.ai.ui.CriticalAlertActivity

/**
 * Intercepts incoming calls, performs lightweight AI scoring, and can auto-hangup
 * when risk is critical. Processing is done locally; no network calls are made.
 */
class SentinelCallScreeningService : CallScreeningService() {

    private val riskScoring by lazy { RiskScoring() }
    private val whisperEngine by lazy { WhisperEngine() }
    private val overlay by lazy { OverlayController(this) }

    override fun onScreenCall(callDetails: Call.Details) {
        SentinelGuardianService.start(this)
        val number = callDetails.handle?.schemeSpecificPart ?: "Unknown"
        val transcript = whisperEngine.transcribe()
        val risk = riskScoring.score(transcript)
        val riskLevel = toRiskLevel(risk.score)

        val event = GuardianEvent(
            source = "Call from $number",
            content = transcript,
            score = risk.score,
            riskLevel = riskLevel
        )
        GuardianEventStore.addEvent(event)

        when (riskLevel) {
            RiskLevel.CRITICAL -> {
                overlay.showCritical()
                launchCriticalAlert(risk.score, transcript)
                respondToCall(callDetails, CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(true)
                    .setSkipNotification(true)
                    .build())
            }
            RiskLevel.WARNING -> {
                overlay.showWarning()
                allowCall(callDetails)
            }
            RiskLevel.SAFE -> allowCall(callDetails)
        }
    }

    private fun allowCall(callDetails: Call.Details) {
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setSilenceCall(false)
                .build()
        )
    }

    private fun launchCriticalAlert(score: Int, detail: String) {
        val intent = Intent(this, CriticalAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(CriticalAlertActivity.EXTRA_SCORE, score)
            putExtra(CriticalAlertActivity.EXTRA_DETAIL, detail)
        }
        startActivity(intent)
    }

    private fun toRiskLevel(score: Int): RiskLevel = when {
        score >= 80 -> RiskLevel.CRITICAL
        score in 40..79 -> RiskLevel.WARNING
        else -> RiskLevel.SAFE
    }
}

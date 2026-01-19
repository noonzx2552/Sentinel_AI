package com.sentinel.ai.service

import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import com.sentinel.ai.ai.RiskScoring
import com.sentinel.ai.ai.WhisperEngine
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.security.NumberChecker
import com.sentinel.ai.ui.CriticalAlertActivity
import com.sentinel.ai.utils.ContactLookup
import com.sentinel.ai.utils.KnownNumberRepository
import com.sentinel.ai.utils.NotificationHelper
import com.sentinel.ai.utils.OverlayController
import com.sentinel.ai.utils.PressureAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Intercepts incoming calls, performs lightweight AI scoring, and can auto-hangup
 * when risk is critical. Processing is done locally; no network calls are made.
 */
class SentinelCallScreeningService : CallScreeningService() {

    private val riskScoring by lazy { RiskScoring() }
    private val whisperEngine by lazy { WhisperEngine(applicationContext) }
    private val pressureAnalyzer by lazy { PressureAnalyzer() }
    private val overlay by lazy { OverlayController(this) }
    private val notificationHelper by lazy { NotificationHelper(this) }
    private val numberChecker by lazy { NumberChecker(this) }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onScreenCall(callDetails: Call.Details) {
        try {
            SentinelGuardianService.start(this)
            val number = callDetails.handle?.schemeSpecificPart ?: "Unknown"
            val contactName = ContactLookup.getContactName(this, number)
            val known = KnownNumberRepository.lookup(number) ?: KnownNumberRepository.heuristic(number)
            
            // Removed: whisperEngine.transcribe() - Cannot transcribe before call starts!
            // Removed: pressureAnalyzer - No transcript yet.

            val riskLevel = known?.riskLevel ?: RiskLevel.SAFE
            val displayName = contactName ?: known?.displayName ?: "Unknown caller"
            val reason = known?.reason ?: ""

            // Log basic event
            val event = GuardianEvent(
                source = "Call from $number",
                content = "Incoming call ring. Known status: $riskLevel",
                score = 0,
                riskLevel = riskLevel
            )
            GuardianEventStore.addEvent(event)

            try {
                overlay.showCallerInfo(
                    name = displayName,
                    number = number,
                    riskLevel = riskLevel,
                    reason = reason,
                    reasonOverride = if (contactName == null) "Searching..." else null,
                    gravity = android.view.Gravity.CENTER
                )
            } catch (e: Exception) {
                // Ignore overlay error if permission missing
            }

            when (riskLevel) {
                RiskLevel.CRITICAL -> {
                    try {
                        // overlay.showCritical() // Removed to prevent overwriting the card overlay
                        notifyCaretaker(displayName, number, riskLevel, reason)
                    } catch (e: Exception) {}
                    
                    respondToCall(
                        callDetails,
                        CallResponse.Builder()
                            .setDisallowCall(true)
                            .setRejectCall(true)
                            .setSkipCallLog(true)
                            .setSkipNotification(true)
                            .build()
                    )
                }
                RiskLevel.WARNING -> {
                    try {
                        // overlay.showWarning() // Removed to prevent overwriting the card overlay
                        notifyCaretaker(displayName, number, riskLevel, reason)
                    } catch (e: Exception) {}
                    allowCall(callDetails)
                }
                RiskLevel.SAFE -> allowCall(callDetails)
            }

            // Background enrichment: provider/country + reports
            serviceScope.launch {
                runCatching { numberChecker.check(number) }
                    .onSuccess { result ->
                        if (result.carrier != null || result.countryName != null) {
                            GuardianEventStore.addEvent(
                                GuardianEvent(
                                    source = "Caller info",
                                    content = "Provider: ${result.carrier ?: "-"} | Country: ${result.countryName ?: result.region ?: "-"}",
                                    score = result.score,
                                    riskLevel = riskLevel
                                )
                            )
                        }
                        
                        // Show caller info overlay with enriched data
                        val effectiveRisk = if (result.reportCount > 0) RiskLevel.CRITICAL else riskLevel
                        val regionDisplay = result.countryName ?: result.region ?: ""
                        val reportSummary = if (result.reportCount > 0) {
                            result.reportDetails.joinToString(" | ").take(140)
                        } else {
                            null
                        }
                        
                        try {
                            overlay.showCallerInfo(
                                name = displayName,
                                number = number,
                                riskLevel = effectiveRisk,
                                reason = reason,
                                carrier = result.carrier,
                                region = regionDisplay,
                                reportCount = result.reportCount,
                                reportSummary = reportSummary,
                                gravity = android.view.Gravity.CENTER
                            )
                        } catch (_: Exception) {}
                        
                        if (result.reportCount > 0) {
                            val reportText = "พบรายงาน ${result.reportCount} รายการ | ${result.reportDetails.joinToString(" / ").take(140)}"
                            GuardianEventStore.addEvent(
                                GuardianEvent(
                                    source = "BlacklistSeller",
                                    content = reportText,
                                    score = (100 - result.reportCount * 10).coerceIn(0, 100),
                                    riskLevel = RiskLevel.CRITICAL
                                )
                            )
                            notifyCaretaker(displayName, number, RiskLevel.CRITICAL, reportText)
                        }
                    }
                    .onFailure { e ->
                        GuardianEventStore.addEvent(
                            GuardianEvent(
                                source = "Caller info",
                                content = "Report lookup failed: ${e.message}",
                                score = 0,
                                riskLevel = RiskLevel.SAFE
                            )
                        )
                    }
            }
        } catch (e: Exception) {
            // Absolute safety net: Allow call if anything crashes
            try {
                respondToCall(callDetails, CallResponse.Builder().build())
            } catch (ignore: Exception) {}
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

    private fun notifyCaretaker(name: String, number: String, riskLevel: RiskLevel, reason: String) {
        val title = when (riskLevel) {
            RiskLevel.CRITICAL -> "Call flagged: CRITICAL"
            RiskLevel.WARNING -> "Call flagged: WARNING"
            RiskLevel.SAFE -> "Call flagged"
        }
        val detail = if (reason.isBlank()) "" else " | $reason"
        val body = "$name ($number) has risk level ${riskLevel.name}$detail"
        notificationHelper.sendCaretakerAlert(title, body)
    }

    private fun maxRisk(a: RiskLevel, b: RiskLevel): RiskLevel {
        return if (a == RiskLevel.CRITICAL || b == RiskLevel.CRITICAL) {
            RiskLevel.CRITICAL
        } else if (a == RiskLevel.WARNING || b == RiskLevel.WARNING) {
            RiskLevel.WARNING
        } else {
            RiskLevel.SAFE
        }
    }

    private fun buildReason(knownReason: String?, behavior: RiskScoring.BehaviorFlags): String {
        val parts = mutableListOf<String>()
        knownReason?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        val behaviorSignals = behaviorSignals(behavior)
        if (behaviorSignals.isNotBlank()) parts.add(behaviorSignals)
        if (parts.isEmpty()) parts.add("Not in contacts")
        return parts.joinToString(" | ")
    }

    private fun behaviorSignals(flags: RiskScoring.BehaviorFlags): String {
        val signals = mutableListOf<String>()
        if (flags.pressureDetected) signals.add("pressure cues")
        if (flags.interruptionDetected) signals.add("interruptions")
        if (flags.logicConflict) signals.add("logic conflict")
        return if (signals.isEmpty()) "" else "Signals: ${signals.joinToString(", ")}"
    }
}

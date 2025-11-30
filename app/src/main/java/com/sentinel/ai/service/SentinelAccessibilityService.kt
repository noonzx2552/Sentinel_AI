package com.sentinel.ai.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.sentinel.ai.ai.RiskScoring
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.utils.OverlayController
import com.sentinel.ai.utils.SensitiveAppBypass

/**
 * Monitors chat app text to surface scam warnings. Everything is analyzed locally and discarded.
 */
class SentinelAccessibilityService : AccessibilityService() {

    private val allowedPackages = setOf(
        "com.linecorp.line.android",
        "com.facebook.orca",
        "com.facebook.katana",
        "com.instagram.android",
        "com.whatsapp"
    )
    private val riskScoring = RiskScoring()
    private val overlay by lazy { OverlayController(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        SentinelGuardianService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            SensitiveAppBypass.updateForeground(pkg)
            overlay.dismissIfBlocked()
        }
        if (!allowedPackages.contains(pkg)) return
        val text = event.text?.joinToString(" ")?.trim().orEmpty()
        if (text.isEmpty()) return

        val flags = RiskScoring.BehaviorFlags(
            pressureDetected = text.contains("ด่วน", ignoreCase = true) || text.contains("urgent", ignoreCase = true),
            interruptionDetected = text.contains("ตอบ") || text.contains("now", ignoreCase = true),
            logicConflict = text.contains("โอน") && text.contains("เจ้าหน้าที่")
        )
        val riskResult = riskScoring.score(text, flags)
        val riskLevel = toRiskLevel(riskResult.score)
        val eventItem = GuardianEvent(
            source = "Chat: $pkg",
            content = text,
            score = riskResult.score,
            riskLevel = riskLevel
        )
        GuardianEventStore.addEvent(eventItem)
        when (riskLevel) {
            RiskLevel.WARNING -> overlay.showWarning()
            RiskLevel.CRITICAL -> overlay.showCritical()
            RiskLevel.SAFE -> overlay.dismiss()
        }
    }

    override fun onInterrupt() {
        overlay.dismiss()
    }

    private fun toRiskLevel(score: Int): RiskLevel = when {
        score >= 80 -> RiskLevel.CRITICAL
        score in 40..79 -> RiskLevel.WARNING
        else -> RiskLevel.SAFE
    }
}

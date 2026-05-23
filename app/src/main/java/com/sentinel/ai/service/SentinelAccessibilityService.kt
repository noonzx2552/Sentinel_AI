package com.sentinel.ai.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sentinel.ai.ai.RiskScoring
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.utils.AllowedAppGate
import com.sentinel.ai.utils.OverlayController
import com.sentinel.ai.utils.SensitiveAppBypass
import org.json.JSONArray

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
    private val smsPackages = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.android.messaging",
        "com.miui.mms",
        "com.coloros.mms",
        "com.vivo.messaging",
        "com.huawei.mms",
        "com.transsion.message"
    )
    private val riskScoring = RiskScoring()
    private val overlay by lazy { OverlayController(this) }
    private var lastSmsWarningHash: Int = 0
    private var lastSmsWarningAt: Long = 0L

    companion object {
        private const val SMS_WARNING_COOLDOWN_MS = 8_000L
        private const val MAX_SCREEN_TEXT_CHARS = 1_200
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        SentinelGuardianService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            SensitiveAppBypass.updateForeground(pkg)
            AllowedAppGate.updateForeground(pkg)
            overlay.dismissIfBlocked()
        }
        if (smsPackages.contains(pkg)) {
            inspectSmsScreen(pkg, event)
            return
        }
        if (!allowedPackages.contains(pkg)) return
        val text = event.text?.joinToString(" ")?.trim().orEmpty()
        if (text.isEmpty()) return

        val flags = behaviorFlags(text)
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

    private fun inspectSmsScreen(pkg: String, event: AccessibilityEvent) {
        val visibleText = collectVisibleText(rootInActiveWindow)
        val eventText = event.text?.joinToString(" ")?.trim().orEmpty()
        val text = listOf(visibleText, eventText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
            .take(MAX_SCREEN_TEXT_CHARS)
        if (text.length < 12) return

        val decision = riskScoring.decide(
            text = text,
            behaviorFlags = behaviorFlags(text),
            extraSourceTags = listOf("SMS", pkg)
        )
        if (decision.riskLevel == RiskLevel.SAFE) return

        val now = System.currentTimeMillis()
        val hash = text.hashCode()
        if (hash == lastSmsWarningHash && now - lastSmsWarningAt < SMS_WARNING_COOLDOWN_MS) return
        lastSmsWarningHash = hash
        lastSmsWarningAt = now

        GuardianEventStore.addEvent(
            GuardianEvent(
                source = "SMS: $pkg",
                content = text.take(240),
                score = decision.score,
                riskLevel = decision.riskLevel,
                reasonsJson = JSONArray(decision.reasons).toString(),
                sourceTagsJson = JSONArray(decision.sourceTags).toString()
            )
        )
        overlay.showSmsRiskAlert(
            sender = extractSmsSender(text),
            score = decision.score,
            reason = decision.reasons.firstOrNull().orEmpty()
        )
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val parts = mutableListOf<String>()
        fun visit(current: AccessibilityNodeInfo) {
            current.text?.toString()?.trim()?.takeIf { it.length >= 2 }?.let(parts::add)
            current.contentDescription?.toString()?.trim()?.takeIf { it.length >= 2 }?.let(parts::add)
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { child ->
                    visit(child)
                }
            }
        }
        visit(node)
        return parts.distinct().joinToString(" ")
    }

    private fun extractSmsSender(text: String): String {
        val sender = Regex("""(?:\+?66|0)\d{8,9}|[A-Za-z][A-Za-z0-9._-]{2,20}""")
            .find(text)
            ?.value
            .orEmpty()
        return sender.take(32)
    }

    private fun behaviorFlags(text: String): RiskScoring.BehaviorFlags {
        val lower = text.lowercase()
        return RiskScoring.BehaviorFlags(
            pressureDetected = lower.contains("ด่วน") ||
                lower.contains("urgent") ||
                lower.contains("ทันที") ||
                lower.contains("หมดอายุ"),
            interruptionDetected = lower.contains("เดี๋ยวนี้") ||
                lower.contains("now") ||
                lower.contains("ภายใน") ||
                lower.contains("คลิก"),
            logicConflict = (lower.contains("โอน") && lower.contains("เจ้าหน้าที่")) ||
                (lower.contains("ตำรวจ") && lower.contains("โอน")) ||
                (lower.contains("otp") && lower.contains("แจ้ง")) ||
                (lower.contains("รหัส") && lower.contains("ยืนยัน"))
        )
    }

    private fun toRiskLevel(score: Int): RiskLevel = when {
        score >= 80 -> RiskLevel.CRITICAL
        score in 40..79 -> RiskLevel.WARNING
        else -> RiskLevel.SAFE
    }
}

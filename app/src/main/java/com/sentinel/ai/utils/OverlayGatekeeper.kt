package com.sentinel.ai.utils

import java.util.Collections
import java.util.WeakHashMap
import com.sentinel.ai.model.RiskLevel

/**
 * Keeps weak references to overlay controllers so we can dismiss all overlays
 * when a sensitive app (e.g., banking) is foreground.
 */
object OverlayGatekeeper {
    private val controllers = Collections.newSetFromMap(WeakHashMap<OverlayController, Boolean>())
    private val lastShownByNumber = mutableMapOf<String, ShownWarning>()
    private val dismissedNumbers = mutableMapOf<String, Long>()
    private const val COOLDOWN_MS = 30_000L
    private const val DISMISS_SESSION_MS = 30 * 60_000L

    fun register(controller: OverlayController) {
        controllers.add(controller)
    }

    fun dismissAll() {
        controllers.forEach { it.dismiss() }
    }

    fun shouldShow(number: String, riskLevel: RiskLevel, now: Long = System.currentTimeMillis()): Boolean {
        cleanup(now)
        val key = normalize(number)
        if (key.isBlank()) return true
        dismissedNumbers[key]?.let { until ->
            if (now < until && riskLevel != RiskLevel.CRITICAL) return false
        }
        val previous = lastShownByNumber[key] ?: return true.also {
            lastShownByNumber[key] = ShownWarning(riskLevel, now)
        }
        val escalated = riskWeight(riskLevel) > riskWeight(previous.riskLevel)
        val cooledDown = now - previous.timestampMs >= COOLDOWN_MS
        if (escalated || cooledDown || riskLevel == RiskLevel.CRITICAL && previous.riskLevel != RiskLevel.CRITICAL) {
            lastShownByNumber[key] = ShownWarning(riskLevel, now)
            return true
        }
        return false
    }

    fun dismissNumberForSession(number: String, now: Long = System.currentTimeMillis()) {
        val key = normalize(number)
        if (key.isNotBlank()) dismissedNumbers[key] = now + DISMISS_SESSION_MS
    }

    private fun cleanup(now: Long) {
        dismissedNumbers.entries.removeAll { it.value <= now }
        lastShownByNumber.entries.removeAll { now - it.value.timestampMs > DISMISS_SESSION_MS }
    }

    private fun normalize(number: String): String = number.filter { it.isDigit() }

    private fun riskWeight(level: RiskLevel): Int = when (level) {
        RiskLevel.SAFE -> 0
        RiskLevel.WARNING -> 1
        RiskLevel.CRITICAL -> 2
    }

    private data class ShownWarning(val riskLevel: RiskLevel, val timestampMs: Long)
}

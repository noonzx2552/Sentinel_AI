package com.sentinel.ai.utils

import com.sentinel.ai.ai.RiskScoring
import kotlin.math.max

/**
 * Heuristic detector for aggressive pressure / scammy behavior in speech transcripts.
 * Uses Thai + English urgency words, authority cues, and intensity signals (all-caps, repeats).
 */
class PressureAnalyzer {
    private val pressureKeywords = listOf(
        "ด่วน", "เดี๋ยวนี้", "ทันที", "โอน", "ภายใน", "ห้ามวาง", "รอสาย", "รีบ", "แชทนี้ห้ามบอกใคร",
        "otp", "one time password", "verify now", "transfer now", "pay now", "act now", "right now",
        "arrest", "police", "lawsuit", "warrant", "freeze", "account freeze", "bank officer",
        "หมายจับ", "จับกุม", "อายัด", "บัญชี", "ศาล", "ผู้พิพากษา", "เจ้าหน้าที่", "สายด่วน"
    )

    fun analyze(text: String): RiskScoring.BehaviorFlags {
        val lower = text.lowercase()
        val pressureHit = pressureKeywords.any { lower.contains(it) }
        val manyExclaim = countExclaim(text) >= 2
        val allCapsSpike = uppercaseRatio(text) > 0.35f && text.length > 12
        val repeatCommand = hasRepeatCommands(lower)

        val pressureDetected = pressureHit || manyExclaim || allCapsSpike || repeatCommand
        val interruptionDetected = repeatCommand || lower.contains("เดี๋ยวนี้") || lower.contains("right now")
        val logicConflict = lower.contains("ตำรวจ") && lower.contains("โอน") ||
            lower.contains("police") && lower.contains("transfer") ||
            lower.contains("ศาล") && lower.contains("บัญชี")

        return RiskScoring.BehaviorFlags(
            pressureDetected = pressureDetected,
            interruptionDetected = interruptionDetected,
            logicConflict = logicConflict
        )
    }

    private fun uppercaseRatio(text: String): Float {
        if (text.isEmpty()) return 0f
        val upper = text.count { it.isUpperCase() }
        val letters = max(1, text.count { it.isLetter() })
        return upper.toFloat() / letters
    }

    private fun countExclaim(text: String): Int = text.count { it == '!' }

    private fun hasRepeatCommands(lower: String): Boolean {
        val patterns = listOf("เร็ว", "รีบ", "โอน", "now", "hurry", "quick", "fast")
        val hits = patterns.count { pattern ->
            lower.split(" ").count { word -> word.contains(pattern) } >= 2
        }
        return hits >= 1
    }
}

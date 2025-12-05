package com.sentinel.ai.ai

import kotlin.math.roundToInt

/**
 * Lightweight, on-device risk scoring. All processing is ephemeral and never leaves the device.
 */
class RiskScoring(private val nlpInference: NLPInference = NLPInference()) {

    data class BehaviorFlags(
        val pressureDetected: Boolean = false,
        val interruptionDetected: Boolean = false,
        val logicConflict: Boolean = false
    )

    data class RiskResult(
        val score: Int,
        val intent: NLPInference.Intent
    )

    private val suspiciousKeywords = listOf(
        "otp", "giftcard", "transfer", "urgent", "account", "verify", "password", "reward", "prize",
        "ด่วน", "เดี๋ยวนี้", "ทันที", "โอน", "บัญชี", "อายัด", "หมายจับ", "เงินฝาก", "คดี", "เจ้าหน้าที่",
        "arrest", "police", "warrant", "freeze", "transfer now", "pay now", "bank officer",
        "ศาล", "ผู้พิพากษา", "จับกุม", "ข้อมูลส่วนตัว", "ชำระค่าปรับ"
    )

    fun score(text: String, behaviorFlags: BehaviorFlags = BehaviorFlags()): RiskResult {
        val intent = nlpInference.classify(text)
        val normalizedKeyword = keywordWeight(text, intent)
        val behavior = behaviorWeight(behaviorFlags)
        val logic = logicWeight(behaviorFlags)

        val raw = (0.4f * normalizedKeyword) + (0.35f * behavior) + (0.25f * logic)
        val score = (raw * 100).roundToInt().coerceIn(0, 100)
        return RiskResult(score, intent)
    }

    private fun keywordWeight(text: String, intent: NLPInference.Intent): Float {
        val lower = text.lowercase()
        val hits = suspiciousKeywords.count { lower.contains(it) }
        val keywordSignal = (hits / 3f).coerceAtMost(1f)
        val intentBias = when (intent) {
            NLPInference.Intent.THREAT -> 0.8f
            NLPInference.Intent.PHISHING -> 0.7f
            NLPInference.Intent.REWARD -> 0.5f
            NLPInference.Intent.SAFE -> 0.0f
        }
        return (keywordSignal + intentBias).coerceAtMost(1f)
    }

    private fun behaviorWeight(flags: BehaviorFlags): Float {
        var value = 0f
        if (flags.pressureDetected) value += 0.5f
        if (flags.interruptionDetected) value += 0.3f
        return value.coerceAtMost(1f)
    }

    private fun logicWeight(flags: BehaviorFlags): Float {
        return if (flags.logicConflict) 1f else 0.1f
    }
}

package com.sentinel.ai.ai

/**
 * Mock NLP intent classifier. In production this would wrap an on-device TFLite model.
 * No data is sent off-device; text is kept in memory for the duration of analysis only.
 */
class NLPInference {
    enum class Intent { THREAT, REWARD, PHISHING, SAFE }

    fun classify(text: String): Intent {
        val lower = text.lowercase()
        return when {
            listOf("จับกุม", "หมายศาล", "ตำรวจ", "ขู่", "threat", "arrest").any(lower::contains) -> Intent.THREAT
            listOf("รางวัล", "โอนคืน", "คืนเงิน", "reward", "bonus", "prize").any(lower::contains) -> Intent.REWARD
            listOf("otp", "รหัส", "password", "verify", "account", "ข้อมูลส่วนตัว").any(lower::contains) -> Intent.PHISHING
            else -> Intent.SAFE
        }
    }
}

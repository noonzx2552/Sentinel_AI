package com.sentinel.ai.model

data class GuardianEvent(
    val source: String,
    val content: String,
    val score: Int,
    val riskLevel: RiskLevel,
    val timestamp: Long = System.currentTimeMillis()
)

enum class RiskLevel {
    SAFE, WARNING, CRITICAL
}

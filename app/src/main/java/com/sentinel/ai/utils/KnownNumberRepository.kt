package com.sentinel.ai.utils

import com.sentinel.ai.model.RiskLevel

/**
 * Lightweight, on-device number knowledge base.
 * You can extend this to load from local DB/cache; here we seed common patterns.
 */
object KnownNumberRepository {

    data class NumberInfo(
        val displayName: String,
        val riskLevel: RiskLevel,
        val reason: String
    )

    // Example seeded data. Real app can hydrate from local DB / cached server data.
    private val known = mapOf(
        "1669" to NumberInfo("Emergency Medical", RiskLevel.SAFE, "Official emergency line"),
        "191" to NumberInfo("Police Hotline", RiskLevel.SAFE, "Official police hotline"),
        "1441" to NumberInfo("Anti-Fraud Hotline", RiskLevel.SAFE, "Anti-fraud center"),
        "025560555" to NumberInfo("Revenue Dept.", RiskLevel.WARNING, "Government number - verify caller identity")
    )

    fun lookup(number: String): NumberInfo? {
        val normalized = number.filter { it.isDigit() || it == '+' }
        return known[normalized] ?: known[number]
    }

    /**
        * Very rough heuristic: personal-looking mobile numbers claiming to be police are suspicious.
        */
    fun heuristic(number: String): NumberInfo? {
        val normalized = number.filter { it.isDigit() || it == '+' }
        if (normalized.isBlank()) return null
        val isMobile = normalized.length in 9..11 && normalized.startsWith("0")
        if (isMobile) {
            return NumberInfo(
                displayName = "Unverified mobile",
                riskLevel = RiskLevel.WARNING,
                reason = "Mobile number not in contacts; verify caller identity"
            )
        }
        return null
    }
}

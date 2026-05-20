package com.sentinel.ai.ai

import com.sentinel.ai.model.RiskLevel

data class RiskDecision(
    val score: Int,
    val riskLevel: RiskLevel,
    val reasons: List<String>,
    val sourceTags: List<String>
)

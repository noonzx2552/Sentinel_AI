package com.sentinel.ai.model

enum class ProtectionMode {
    PLAYBACK_CAPTURE,
    MIC_FALLBACK,
    NO_AUDIO,
    NUMBER_ONLY,
    ACCESSIBILITY,
    UNKNOWN
}

data class CallerOverlayUiState(
    val phoneNumber: String,
    val displayName: String?,
    val riskLevel: RiskLevel,
    val riskScore: Int,
    val reasons: List<String>,
    val sourceTags: List<String>,
    val protectionMode: ProtectionMode,
    val liveTranscript: String?,
    val isExpanded: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

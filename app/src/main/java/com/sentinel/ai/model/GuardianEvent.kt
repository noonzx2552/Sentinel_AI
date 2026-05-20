package com.sentinel.ai.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class GuardianEvent(
    val source: String,
    val content: String,
    val score: Int,
    val riskLevel: RiskLevel,
    val timestamp: Long = System.currentTimeMillis(),
    val reasonsJson: String = "[]",
    val sourceTagsJson: String = "[]",
    val protectionMode: String? = null,
    val phoneNumber: String? = null,
    val displayName: String? = null,
    val transcriptSnippet: String? = null,
    val audioMode: String? = null,
    val confidence: Float? = null,
    val latencyMs: Long? = null,
    @PrimaryKey(autoGenerate = true) val id: Int = 0
)

enum class RiskLevel {
    SAFE, WARNING, CRITICAL
}

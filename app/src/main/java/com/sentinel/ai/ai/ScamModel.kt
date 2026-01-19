package com.sentinel.ai.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Abstraction for the on-device scam intent model.
 *
 * The concrete implementation lives in the debug source set; release builds
 * fall back to a no-op stub so the feature is opt-in for local testing only.
 */
interface ScamModel {
    data class Prediction(
        val label: String,
        val confidence: Float
    )

    /**
     * Ensure the model and tokenizer are ready on disk.
     * Call from a background dispatcher to avoid blocking UI.
     */
    suspend fun ensureLoaded(): Boolean

    /**
     * Run classification on the provided text. Returns null if the model
     * is unavailable in this build or not yet loaded.
     */
    suspend fun predict(text: String): Prediction?
}

object ScamModelProvider {
    /**
     * Returns a debug-only model instance if present on the classpath.
     * Release builds gracefully fall back to a no-op implementation.
     */
    fun create(context: Context): ScamModel {
        return try {
            val clazz = Class.forName("com.sentinel.ai.ai.DebugScamModel")
            val ctor = clazz.getConstructor(Context::class.java)
            ctor.newInstance(context.applicationContext) as ScamModel
        } catch (e: Exception) {
            Log.d("ScamModelProvider", "Debug scam model not available: ${e.message}")
            NoopScamModel
        }
    }
}

object NoopScamModel : ScamModel {
    override suspend fun ensureLoaded(): Boolean = false
    override suspend fun predict(text: String): ScamModel.Prediction? = null
}

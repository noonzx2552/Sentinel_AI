package com.sentinel.ai.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sentinel.ai.service.InternalAudioPressureService

/**
 * Public entrypoint for the Internal Pressure Monitor feature.
 * Starts/stops the foreground service that captures internal audio and dispatches transcripts.
 */
object PressureMonitor {

    fun start(context: Context, projectionData: Intent, resultCode: Int) {
        val startIntent = Intent(context, InternalAudioPressureService::class.java).apply {
            action = InternalAudioPressureService.ACTION_START
            putExtra(InternalAudioPressureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(InternalAudioPressureService.EXTRA_RESULT_DATA, projectionData)
        }
        ContextCompat.startForegroundService(context, startIntent)
    }

    fun stop(context: Context) {
        val stopIntent = Intent(context, InternalAudioPressureService::class.java).apply {
            action = InternalAudioPressureService.ACTION_STOP
        }
        context.startService(stopIntent)
    }
}

package com.sentinel.ai.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.sentinel.ai.service.InternalAudioPressureService

/**
 * Public entrypoint for the Internal Pressure Monitor feature.
 * Starts/stops the foreground service that captures internal audio and dispatches transcripts.
 */
object PressureMonitor {

    fun start(context: Context, projectionData: Intent, resultCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Internal audio capture requires Android 10+. Ignoring start request.")
            return
        }
        val startIntent = Intent(context, InternalAudioPressureService::class.java).apply {
            action = InternalAudioPressureService.ACTION_START
            putExtra(InternalAudioPressureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(InternalAudioPressureService.EXTRA_RESULT_DATA, projectionData)
        }
        ContextCompat.startForegroundService(context, startIntent)
    }

    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val stopIntent = Intent(context, InternalAudioPressureService::class.java).apply {
            action = InternalAudioPressureService.ACTION_STOP
        }
        context.startService(stopIntent)
    }

    private const val TAG = "PressureMonitor"
}

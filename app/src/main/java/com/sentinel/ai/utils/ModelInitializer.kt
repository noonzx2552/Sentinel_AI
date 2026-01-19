package com.sentinel.ai.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.sentinel.ai.R

/**
 * Stubbed initializer that represents downloading/loading local AI models.
 * Real builds should stream models to disk and keep inference on-device.
 */
object ModelInitializer {
    fun initialize(context: Context, onReady: (() -> Unit)? = null) {
        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(context, context.getString(R.string.model_initializer_ready), Toast.LENGTH_SHORT).show()
            onReady?.invoke()
        }, 600)
    }
}

package com.sentinel.ai.utils

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.sentinel.ai.R
import com.sentinel.ai.utils.SensitiveAppBypass
import com.sentinel.ai.utils.OverlayGatekeeper

class OverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var currentView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    init {
        OverlayGatekeeper.register(this)
    }

    fun showWarning() {
        showLayout(R.layout.view_overlay_warning, autoDismissMs = 6000L)
    }

    fun showCritical() {
        showLayout(R.layout.view_overlay_critical, autoDismissMs = 12000L)
    }

    fun showListening() {
        // Persistent banner while call monitoring is active.
        showLayout(R.layout.view_overlay_listening, autoDismissMs = 0L)
    }

    private fun showLayout(layoutId: Int, autoDismissMs: Long) {
        if (!Settings.canDrawOverlays(context)) return
        if (SensitiveAppBypass.isBlocked()) {
            dismiss()
            return
        }
        handler.post {
            dismissInternal()
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(layoutId, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
            }
            windowManager.addView(view, params)
            currentView = view
            if (autoDismissMs > 0) {
                handler.postDelayed({ dismissInternal() }, autoDismissMs)
            }
        }
    }

    fun dismiss() {
        handler.post { dismissInternal() }
    }

    fun dismissIfBlocked() {
        if (SensitiveAppBypass.isBlocked()) {
            dismiss()
        }
    }

    private fun dismissInternal() {
        currentView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
            currentView = null
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }
}

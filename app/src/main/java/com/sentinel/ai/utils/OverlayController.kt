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
    private var liveTranscriptView: android.widget.TextView? = null
    private var liveTranscriptParams: WindowManager.LayoutParams? = null
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

    fun showLiveTranscript(initial: String) {
        if (!Settings.canDrawOverlays(context)) return
        if (SensitiveAppBypass.isBlocked()) {
            dismiss()
            return
        }
        handler.post {
            dismissInternal()
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.view_overlay_live_transcript, null)
            liveTranscriptView = view.findViewById(R.id.tvOverlayTranscript)
            liveTranscriptView?.text = initial
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 240
            }
            attachDraggable(view, params)
            windowManager.addView(view, params)
            currentView = view
            liveTranscriptParams = params
        }
    }

    fun updateLiveTranscript(text: String) {
        handler.post { liveTranscriptView?.text = text }
    }

    fun showCallerInfo(name: String, number: String, riskLevel: com.sentinel.ai.model.RiskLevel, reason: String) {
        if (!Settings.canDrawOverlays(context)) return
        if (SensitiveAppBypass.isBlocked()) {
            dismiss()
            return
        }
        handler.post {
            dismissInternal()
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.view_overlay_caller_info, null)
            view.findViewById<android.widget.TextView>(R.id.callerName).text = name
            view.findViewById<android.widget.TextView>(R.id.callerNumber).text = number
            view.findViewById<android.widget.TextView>(R.id.callerReason).text = reason
            val badge = view.findViewById<android.widget.TextView>(R.id.callerRisk)
            badge.text = riskLevel.name
            val color = when (riskLevel) {
                com.sentinel.ai.model.RiskLevel.SAFE -> android.graphics.Color.parseColor("#2E7D32")
                com.sentinel.ai.model.RiskLevel.WARNING -> android.graphics.Color.parseColor("#F9A825")
                com.sentinel.ai.model.RiskLevel.CRITICAL -> android.graphics.Color.parseColor("#C62828")
            }
            badge.setBackgroundColor(color)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }
            windowManager.addView(view, params)
            currentView = view
            handler.postDelayed({ dismissInternal() }, 8000L)
        }
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
            liveTranscriptView = null
            liveTranscriptParams = null
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

    private fun attachDraggable(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    params.x = (initialX + (event.rawX - initialTouchX)).toInt()
                    params.y = (initialY + (event.rawY - initialTouchY)).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }
    }
}

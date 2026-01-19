package com.sentinel.ai.utils

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.sentinel.ai.R
import com.sentinel.ai.utils.SensitiveAppBypass
import com.sentinel.ai.utils.OverlayGatekeeper
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.utils.PermissionUtils

class OverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var currentView: View? = null
    private var liveTranscriptView: android.widget.TextView? = null
    private var liveTranscriptBadge: android.widget.TextView? = null
    private var liveTranscriptParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var callerInfoListener: PhoneStateListener? = null
    private var allowGatekeeperDismiss = true

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
        if (SensitiveAppBypass.isBlocked() || !AllowedAppGate.isAllowed()) {
            dismiss()
            return
        }
        handler.post {
            dismissInternal()
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.view_overlay_live_transcript, null)
            liveTranscriptView = view.findViewById(R.id.tvOverlayTranscript)
            liveTranscriptView?.text = initial
            liveTranscriptBadge = view.findViewById(R.id.tvOverlayRisk)
            view.findViewById<View>(R.id.btnOverlayClose)?.setOnClickListener { dismiss() }
            liveTranscriptBadge?.let {
                it.text = context.getString(R.string.overlay_badge_listening)
                it.background?.setTint(android.graphics.Color.parseColor("#0EA5E9"))
            }
            // Enable scrolling inside the transcript box
            liveTranscriptView?.isVerticalScrollBarEnabled = true
            liveTranscriptView?.movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }
        try {
            windowManager.addView(view, params)
            currentView = view
            liveTranscriptParams = params
        } catch (e: Exception) {
                android.util.Log.e("OverlayController", "Failed to show live transcript overlay: ${e.message}", e)
                liveTranscriptView = null
                liveTranscriptBadge = null
                liveTranscriptParams = null
            }
        }
    }

    fun updateLiveTranscript(text: String) {
        handler.post { liveTranscriptView?.text = text }
    }

    fun updateLiveTranscriptRisk(level: RiskLevel) {
        handler.post {
            liveTranscriptBadge?.let { badge ->
                val (label, color) = when (level) {
                    RiskLevel.SAFE -> context.getString(R.string.risk_level_safe) to android.graphics.Color.parseColor("#16A34A")
                    RiskLevel.WARNING -> context.getString(R.string.risk_level_warning) to android.graphics.Color.parseColor("#FACC15")
                    RiskLevel.CRITICAL -> context.getString(R.string.risk_level_critical) to android.graphics.Color.parseColor("#EF4444")
                }
                badge.text = label
                badge.background?.setTint(color)
            }
        }
    }

    fun showCallerInfo(
        name: String,
        number: String,
        riskLevel: com.sentinel.ai.model.RiskLevel,
        reason: String,
        score: Int = 70,
        isOutgoing: Boolean = false,
        carrier: String? = null,
        region: String? = null,
        reportCount: Int = 0,
        reportSummary: String? = null,
        dismissOnCallState: Boolean = true,
        allowGatekeeperDismiss: Boolean = false,
        reasonOverride: String? = null,
        gravity: Int = Gravity.TOP
    ) {
        handler.post {
            try {
                // Check permission first
                if (!Settings.canDrawOverlays(context)) {
                    android.util.Log.e("OverlayController", "No overlay permission")
                    return@post
                }
                
                if (SensitiveAppBypass.isBlocked()) {
                    android.util.Log.e("OverlayController", "Blocked by sensitive app bypass (Banking)")
                    dismiss()
                    return@post
                }
                
                // Note: We intentionally skip AllowedAppGate check here to support all phone brands (Dialer/InCallUI).
                // Those apps change package names too often to whitelist manually.
                
                dismissInternal()
                this@OverlayController.allowGatekeeperDismiss = allowGatekeeperDismiss
                
                // Create wrapper (compact overlay)
                val wrapper = android.widget.FrameLayout(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                
                // Inflate our content
                val inflater = LayoutInflater.from(context)
                val contentView = inflater.inflate(R.layout.view_overlay_caller_info, wrapper, false)
                val contentParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    this.gravity = gravity or Gravity.CENTER_HORIZONTAL
                    if (gravity == Gravity.TOP) {
                        topMargin = (context.resources.displayMetrics.density * 60).toInt()
                    }
                }
                contentView.layoutParams = contentParams
                contentView.isClickable = true
                contentView.setOnClickListener { }
                
                // Show contact name if available; switch to scammer label when flagged.
                val isScammer = reportCount > 0 || riskLevel == com.sentinel.ai.model.RiskLevel.CRITICAL
                val displayName = if (name.isNotBlank() && !name.equals("Unknown caller", ignoreCase = true)) {
                    name
                } else if (!isScammer) {
                    // If no name and not a scammer, use "Unknown number" or similar as title, 
                    // or just put the number as the title if you prefer. 
                    // Let's stick to the user's request: "Name" (or number if no name) as big text, "Number" as small text.
                    formatPhoneNumber(number)
                } else {
                    context.getString(R.string.overlay_caller_scammer)
                }

                contentView.findViewById<android.widget.TextView>(R.id.tvPhoneNumber)?.text = displayName
                
                // Always show the number in the secondary field
                contentView.findViewById<android.widget.TextView>(R.id.tvCallerNumber)?.apply {
                    val formatted = formatPhoneNumber(number)
                    // If the main title IS the number, hide this secondary one to avoid duplication
                    if (displayName == formatted) {
                        visibility = View.GONE
                    } else {
                        visibility = View.VISIBLE
                        text = formatted
                    }
                }
                
                // Set region
                contentView.findViewById<android.widget.TextView>(R.id.tvRegion)?.text =
                    region?.takeIf { it.isNotBlank() } ?: "-"

                val effectiveRisk = if (reportCount > 0) com.sentinel.ai.model.RiskLevel.CRITICAL else riskLevel
                val badgeColor = when (effectiveRisk) {
                    com.sentinel.ai.model.RiskLevel.SAFE -> android.graphics.Color.parseColor("#16A34A")
                    com.sentinel.ai.model.RiskLevel.WARNING -> android.graphics.Color.parseColor("#FACC15")
                    com.sentinel.ai.model.RiskLevel.CRITICAL -> android.graphics.Color.parseColor("#DC2626")
                }
                contentView.findViewById<View>(R.id.infoBadge)?.background?.setTint(adjustBadgeBg(badgeColor))
                contentView.findViewById<android.widget.TextView>(R.id.ivInfoIcon)?.setTextColor(badgeColor)
                contentView.findViewById<View>(R.id.mascotBadge)?.background?.let { drawable ->
                    if (drawable is android.graphics.drawable.GradientDrawable) {
                        drawable.setColor(badgeColor)
                        drawable.setStroke(3, android.graphics.Color.WHITE)
                    } else {
                        drawable.setTint(badgeColor)
                    }
                }
                
                // Set card background tint based on risk
                val cardView = contentView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.callerInfoCard)
                cardView?.strokeColor = badgeColor

                // Set risk reason
                val reasonView = contentView.findViewById<android.widget.TextView>(R.id.tvRiskReason)
                val reasonText = reasonOverride?.takeIf { it.isNotBlank() } ?: when {
                    reportCount > 0 -> context.getString(R.string.overlay_reports_found_format, reportCount)
                    else -> context.getString(R.string.overlay_no_reports)
                }
                reasonView?.text = reasonText
                
                contentView.findViewById<View>(R.id.btnOverlayClose)?.setOnClickListener {
                    dismiss()
                }
                
                // Add content to wrapper
                wrapper.addView(contentView)
                
                // Window params
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    this.gravity = gravity
                }
                
                // Add to window manager
                windowManager.addView(wrapper, params)
                currentView = wrapper
                
                android.util.Log.i("OverlayController", "Overlay shown successfully")
                if (dismissOnCallState) {
                    attachCallStateDismiss()
                }
            } catch (e: Exception) {
                android.util.Log.e("OverlayController", "Overlay failed: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }

    fun updateCallerInfoLoadingText(text: String) {
        handler.post {
            val reasonView = currentView?.findViewById<android.widget.TextView>(R.id.tvRiskReason)
            reasonView?.text = text
        }
    }


    private fun showLayout(layoutId: Int, autoDismissMs: Long) {
        if (!Settings.canDrawOverlays(context)) return
        if (SensitiveAppBypass.isBlocked() || !AllowedAppGate.isAllowed()) {
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
            try {
                windowManager.addView(view, params)
                currentView = view
                if (autoDismissMs > 0) {
                    handler.postDelayed({ dismissInternal() }, autoDismissMs)
                }
            } catch (e: Exception) {
                android.util.Log.e("OverlayController", "Failed to show overlay layout: ${e.message}", e)
            }
        }
    }

    fun dismiss() {
        dismiss(force = true)
    }

    fun dismiss(force: Boolean) {
        if (!force && !allowGatekeeperDismiss) return
        handler.post { dismissInternal() }
    }

    fun dismissIfBlocked() {
        if (SensitiveAppBypass.isBlocked() || !AllowedAppGate.isAllowed()) {
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
            liveTranscriptBadge = null
            liveTranscriptParams = null
        }
        allowGatekeeperDismiss = true
        detachCallStateDismiss()
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun adjustBadgeBg(color: Int): Int {
        val alpha = 36
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun formatPhoneNumber(number: String): String {
        val clean = number.replace(Regex("[^0-9]"), "")
        return if (clean.length == 10 && clean.startsWith("0")) {
            "${clean.substring(0, 3)} ${clean.substring(3, 6)} ${clean.substring(6)}"
        } else if (clean.length == 9 && !clean.startsWith("0")) {
            // Probably missing leading zero
            "0${clean.substring(0, 2)} ${clean.substring(2, 5)} ${clean.substring(5)}"
        } else {
            number
        }
    }

    @Suppress("DEPRECATION")
    private fun attachCallStateDismiss() {
        if (callerInfoListener != null) return
        if (!PermissionUtils.hasPhoneStatePermission(context)) return
        callerInfoListener = object : PhoneStateListener() {
            @Suppress("DEPRECATION")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                    dismiss()
                }
            }
        }
        try {
            telephonyManager.listen(callerInfoListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (_: SecurityException) {
            callerInfoListener = null
        }
    }

    @Suppress("DEPRECATION")
    private fun detachCallStateDismiss() {
        callerInfoListener?.let {
            try {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            } catch (_: Exception) { }
        }
        callerInfoListener = null
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

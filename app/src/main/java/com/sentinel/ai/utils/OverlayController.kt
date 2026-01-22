package com.sentinel.ai.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.google.android.material.card.MaterialCardView
import com.sentinel.ai.R
import com.sentinel.ai.utils.SensitiveAppBypass
import com.sentinel.ai.utils.OverlayGatekeeper
import com.sentinel.ai.model.RiskLevel

class OverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var currentView: View? = null
    private var liveTranscriptView: android.widget.TextView? = null
    private var liveTranscriptBadge: android.widget.TextView? = null
    private var liveTranscriptTitle: android.widget.TextView? = null
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
            liveTranscriptTitle = view.findViewById(R.id.tvOverlayTitle)
            view.findViewById<View>(R.id.btnOverlayClose)?.setOnClickListener { dismiss() }
            liveTranscriptBadge?.let {
                it.text = "LISTENING"
                it.background?.setTint(android.graphics.Color.parseColor("#0EA5E9"))
            }
            // Enable scrolling inside the transcript box
            liveTranscriptView?.isVerticalScrollBarEnabled = true
            liveTranscriptView?.movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
            val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER or Gravity.START
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

    @JvmOverloads
    fun updateLiveTranscriptRisk(level: RiskLevel, scenarioName: String? = null) {
        handler.post {
            liveTranscriptBadge?.let { badge ->
                val (label, color) = when (level) {
                    RiskLevel.SAFE -> "SAFE" to android.graphics.Color.parseColor("#16A34A")
                    RiskLevel.WARNING -> "WARN" to android.graphics.Color.parseColor("#FACC15")
                    RiskLevel.CRITICAL -> "CRITICAL" to android.graphics.Color.parseColor("#EF4444")
                }
                badge.text = label
                badge.background?.setTint(color)
            }
            if (scenarioName != null) {
                liveTranscriptTitle?.text = context.getString(R.string.scam_keyword_risk_title, scenarioName)
            }
        }
    }

    @JvmOverloads
    fun showCallerInfo(
        name: String,
        number: String,
        riskLevel: com.sentinel.ai.model.RiskLevel,
        reason: String,
        isOutgoing: Boolean = false,
        carrier: String? = null,
        region: String? = null,
        reportCount: Int = 0,
        reportSummary: String? = null,
        dismissOnCallState: Boolean = false,
        allowGatekeeperDismiss: Boolean = false,
        reasonOverride: String? = null,
        gravity: Int = Gravity.CENTER,
        autoDismissMs: Long = 8000L,
        bypassGate: Boolean = false
    ) {
        if (!Settings.canDrawOverlays(context)) return
        if (!bypassGate) {
            if (SensitiveAppBypass.isBlocked() || !AllowedAppGate.isAllowed()) {
                dismiss()
                return
            }
        }
        handler.post {
            dismissInternal()
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.view_overlay_caller_info, null)
            val formattedNum = formatPhoneDisplay(number)
            val unknown = isUnknownName(name)
            val displayName = when {
                reportCount > 0 -> context.getString(R.string.overlay_caller_scammer)
                unknown -> formattedNum
                else -> name
            }
            view.findViewById<android.widget.TextView>(R.id.tvPhoneNumber).text = displayName
            val tvCaller = view.findViewById<android.widget.TextView>(R.id.tvCallerNumber)
            if (unknown && reportCount == 0) {
                tvCaller.visibility = View.GONE
            } else {
                tvCaller.visibility = View.VISIBLE
                tvCaller.text = formattedNum
            }
            val baseReason = (reasonOverride ?: reason).let { r ->
                if (reportSummary != null) if (r.isBlank()) reportSummary else "$r | $reportSummary" else r
            }
            val reasonText = if (reportCount > 0) {
                context.resources.getQuantityString(R.plurals.overlay_found_reports_count, reportCount, reportCount)
            } else baseReason
            view.findViewById<android.widget.TextView>(R.id.tvRiskReason).text = reasonText
            view.findViewById<android.widget.TextView>(R.id.tvRegion).text = region ?: carrier ?: riskLevel.name
            view.findViewById<View>(R.id.btnOverlayClose)?.setOnClickListener { dismiss() }
            val isCritical = riskLevel == com.sentinel.ai.model.RiskLevel.CRITICAL || reportCount > 0
            if (isCritical) {
                view.findViewById<MaterialCardView>(R.id.callerInfoCard)?.strokeColor = Color.parseColor("#EF4444")
                view.findViewById<View>(R.id.mascotBadge)?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                view.findViewById<View>(R.id.infoBadge)?.background = context.getDrawable(R.drawable.overlay_info_badge_critical)
                view.findViewById<View>(R.id.ivInfoIcon)?.visibility = View.GONE
                view.findViewById<ImageView>(R.id.ivInfoIconScammer)?.visibility = View.VISIBLE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                this.gravity = gravity
            }
            windowManager.addView(view, params)
            currentView = view
            if (autoDismissMs > 0L) {
                handler.postDelayed({ dismissInternal() }, autoDismissMs)
            }
        }
    }

    /**
     * Updates the caller overlay with the final result (reason + region + reportCount) without dismissing.
     * When reportCount > 0: name → มิจฉาชีพ, card stroke + shield red.
     */
    fun updateCallerInfoResult(reason: String, region: String, reportCount: Int = 0) {
        handler.post {
            currentView?.findViewById<android.widget.TextView>(R.id.tvRiskReason)?.text = reason
            currentView?.findViewById<android.widget.TextView>(R.id.tvRegion)?.text = region
            if (reportCount > 0) {
                currentView?.findViewById<android.widget.TextView>(R.id.tvPhoneNumber)?.text = context.getString(R.string.overlay_caller_scammer)
                currentView?.findViewById<MaterialCardView>(R.id.callerInfoCard)?.strokeColor = Color.parseColor("#EF4444")
                currentView?.findViewById<View>(R.id.mascotBadge)?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                currentView?.findViewById<View>(R.id.ivInfoIcon)?.visibility = View.GONE
                currentView?.findViewById<ImageView>(R.id.ivInfoIconScammer)?.visibility = View.VISIBLE
            }
        }
    }

    fun updateCallerInfoLoadingText(text: String) {
        handler.post {
            currentView?.findViewById<android.widget.TextView>(R.id.tvRiskReason)?.text = text
        }
    }

    fun showScamAlert(number: String, dismissOnCallState: Boolean = false) {
        if (!Settings.canDrawOverlays(context)) return
        if (SensitiveAppBypass.isBlocked() || !AllowedAppGate.isAllowed()) {
            dismiss()
            return
        }
        handler.post {
            dismissInternal()
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.view_overlay_scam_alert, null)
            view.findViewById<android.widget.TextView>(R.id.tvScamNumber).text = formatPhoneDisplay(number)
            view.findViewById<View>(R.id.btnOverlayClose)?.setOnClickListener { dismiss() }
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
            handler.postDelayed({ dismissInternal() }, 10000L)
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
                gravity = Gravity.CENTER
            }
            windowManager.addView(view, params)
            currentView = view
            if (autoDismissMs > 0) {
                handler.postDelayed({ dismissInternal() }, autoDismissMs)
            }
        }
    }

    fun dismiss(force: Boolean = false) {
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

    /** Format as "085 521 8473" for Thai 10-digit (0xx xxx xxxx). */
    private fun formatPhoneDisplay(number: String?): String {
        if (number.isNullOrBlank()) return ""
        var d = number.replace(Regex("[^0-9]"), "")
        if (d.startsWith("66") && d.length == 11) d = "0" + d.substring(2)
        if (d.length == 10 && d[0] == '0') return "${d[0]}${d.substring(1, 3)} ${d.substring(3, 6)} ${d.substring(6, 10)}"
        if (d.length == 9 && (d[0] == '8' || d[0] == '9')) return "0${d.substring(0, 2)} ${d.substring(2, 5)} ${d.substring(5, 9)}"
        return number
    }

    private fun isUnknownName(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val n = name.trim()
        return n.equals("Unknown", true) || n.equals("Unknown caller", true) || n.equals("ไม่ทราบ", true)
    }
}

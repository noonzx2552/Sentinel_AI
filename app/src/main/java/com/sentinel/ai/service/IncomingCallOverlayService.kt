package com.sentinel.ai.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.sentinel.ai.R
import com.sentinel.ai.model.CallerOverlayUiState
import com.sentinel.ai.model.ProtectionMode
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.security.NumberChecker
import com.sentinel.ai.security.SafetyLevel
import com.sentinel.ai.utils.ContactLookup
import com.sentinel.ai.utils.KnownNumberRepository
import com.sentinel.ai.utils.LastCallStore
import com.sentinel.ai.utils.NotificationHelper
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that shows an incoming-call overlay using existing layouts.
 * Integration points:
 * - Uses KnownNumberRepository + NumberChecker (blacklist + score thresholds).
 * - Inflates existing overlay layouts via WindowManager; no new UI resources.
 */
class IncomingCallOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val telephonyManager by lazy { getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager }
    private val notificationHelper by lazy { NotificationHelper(this) }
    private val overlayController by lazy { OverlayController(this) }

    private var overlayView: View? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var currentNumber: String = ""
    private var currentDisplayName: String = ""
    private var providedName: String? = null
    private var providedRisk: RiskLevel? = null
    private var providedReason: String? = null
    private var providedCarrier: String? = null
    private var providedRegion: String? = null
    private var providedReportCount: Int = 0
    private var lastEnrichedDigits: String = ""
    private var overlayRetryJob: Job? = null
    private var overlayRetryAttempts = 0
    private var overlaySessionActive = false
    private var lastOverlayData: OverlayData? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                endOverlaySession()
                return START_NOT_STICKY
            }
            ACTION_SHOW, null -> {
                overlaySessionActive = true
                cancelOverlayRetry()
                startForegroundSafe(notificationHelper.buildGuardianNotification())
                if (!ensurePermissionsOrRedirect()) {
                    endOverlaySession()
                    return START_NOT_STICKY
                }
                lastEnrichedDigits = ""
                cacheProvidedExtras(intent)
                val number = pickUsableNumber(
                    intent?.getStringExtra(EXTRA_NUMBER),
                    LastCallStore.get()?.number
                )
                currentNumber = number
                showInitialOverlay(number)
                startCallStateListener()
                maybeEnrichWithNumberCheck(number)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCallStateListener()
        endOverlaySession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensurePermissionsOrRedirect(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return false
        }
        if (!PermissionUtils.hasPhoneStatePermission(this)) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return false
        }
        return true
    }

    private fun showInitialOverlay(number: String) {
        val cleanProvidedName = providedName?.takeIf { it.isNotBlank() }
        val cleanProvidedReason = providedReason?.takeIf { it.isNotBlank() }
        val cleanProvidedCarrier = providedCarrier?.takeIf { it.isNotBlank() }
        val cleanProvidedRegion = providedRegion?.takeIf { it.isNotBlank() }
        val contactName = ContactLookup.getContactName(this, number)
        val known = KnownNumberRepository.lookup(number) ?: KnownNumberRepository.heuristic(number)
        val displayName = cleanProvidedName ?: contactName ?: known?.displayName ?: getString(R.string.common_unknown)
        val forcedScammer = isForcedScammer(number)
        val riskLevel = providedRisk
            ?: if (forcedScammer) RiskLevel.WARNING else known?.riskLevel ?: RiskLevel.SAFE
        val reason = when {
            cleanProvidedReason != null -> cleanProvidedReason
            forcedScammer -> getString(R.string.call_status_searching)
            else -> known?.reason ?: getString(R.string.overlay_no_reports)
        }

        val data = OverlayData(
            name = displayName,
            number = number,
            riskLevel = riskLevel,
            reason = reason,
            carrier = cleanProvidedCarrier,
            region = cleanProvidedRegion,
            reportCount = providedReportCount
        )
        currentDisplayName = displayName
        renderOverlay(data)
    }

    private fun enrichWithNumberCheck(number: String) {
        if (number.isBlank()) return
        serviceScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { NumberChecker(this@IncomingCallOverlayService).check(number) }.getOrNull() }
                ?: return@launch
            val mappedRisk = mapSafetyToRisk(result.status, result.score, result.reportCount)
            val reportSummary = if (result.reportCount > 0) {
                result.reportDetails.joinToString(" / ").take(140)
            } else null
            val baseReason = if (result.reportCount > 0) {
                getString(R.string.checknumber_reports_found_format, result.reportCount)
            } else {
                getString(R.string.overlay_no_reports)
            }
            val reason = if (!reportSummary.isNullOrBlank()) {
                "$baseReason | $reportSummary"
            } else {
                baseReason
            }
            val data = OverlayData(
                name = currentDisplayName.ifBlank { getString(R.string.common_unknown) },
                number = result.displayNumber.ifBlank { number },
                riskLevel = mappedRisk,
                reason = reason,
                carrier = result.carrier,
                region = result.countryName ?: result.region,
                reportCount = result.reportCount
            )
            if (mappedRisk == RiskLevel.CRITICAL) {
                delay(SCAM_ALERT_DELAY_MS)
            }
            renderOverlay(data)
        }
    }

    private fun mapSafetyToRisk(status: SafetyLevel, score: Int, reportCount: Int): RiskLevel {
        if (reportCount > 0) return RiskLevel.CRITICAL
        return when (status) {
            SafetyLevel.DANGER -> RiskLevel.CRITICAL
            SafetyLevel.CAUTION -> RiskLevel.WARNING
            SafetyLevel.SAFE -> RiskLevel.SAFE
            SafetyLevel.UNKNOWN -> if (score < 50) RiskLevel.CRITICAL else RiskLevel.WARNING
        }
    }

    private fun renderOverlay(data: OverlayData, resetRetry: Boolean = true) {
        lastOverlayData = data
        if (resetRetry) {
            cancelOverlayRetry()
        }
        val state = CallerOverlayUiState(
            phoneNumber = data.number,
            displayName = data.name,
            riskLevel = data.riskLevel,
            riskScore = when (data.riskLevel) {
                RiskLevel.SAFE -> 18
                RiskLevel.WARNING -> 55
                RiskLevel.CRITICAL -> 86
            },
            reasons = listOf(data.reason).filter { it.isNotBlank() },
            sourceTags = buildList {
                add(getString(R.string.overlay_tag_number_check))
                if (data.reportCount > 0) add(getString(R.string.overlay_tag_blacklist))
            },
            protectionMode = ProtectionMode.NUMBER_ONLY,
            liveTranscript = null,
            isExpanded = data.riskLevel != RiskLevel.SAFE || data.reportCount > 0
        )
        overlayController.showCallerRiskOverlay(state, force = true)
    }

    private fun showCallerInfoOverlay(data: OverlayData) {
        val view = LayoutInflater.from(this).inflate(R.layout.view_overlay_caller_info, null)
        view.findViewById<TextView>(R.id.tvPhoneNumber).text = data.name
        view.findViewById<TextView>(R.id.tvCallerNumber).text = data.number
        view.findViewById<TextView>(R.id.tvRegion).text = data.region ?: data.carrier ?: data.riskLevel.name
        view.findViewById<TextView>(R.id.tvRiskReason).text = data.reason
        view.findViewById<View>(R.id.btnOverlayClose)?.setOnClickListener {
            dismissOverlay()
            stopSelf()
        }

        val card = view.findViewById<MaterialCardView>(R.id.callerInfoCard)
        val infoBadge = view.findViewById<View>(R.id.infoBadge)
        val infoIcon = view.findViewById<TextView>(R.id.ivInfoIcon)
        val scamIcon = view.findViewById<ImageView>(R.id.ivInfoIconScammer)
        val colorRes = when (data.riskLevel) {
            RiskLevel.SAFE -> R.color.bottom_nav_active_green
            RiskLevel.WARNING -> R.color.home_accent_yellow
            RiskLevel.CRITICAL -> R.color.home_accent_red
        }
        val badgeRes = when (data.riskLevel) {
            RiskLevel.SAFE -> R.drawable.overlay_risk_badge_safe
            RiskLevel.WARNING -> R.drawable.overlay_risk_badge_warning
            RiskLevel.CRITICAL -> R.drawable.overlay_risk_badge_critical
        }
        card?.strokeColor = ContextCompat.getColor(this, colorRes)
        infoBadge?.background = ContextCompat.getDrawable(this, badgeRes)
        if (data.riskLevel == RiskLevel.CRITICAL) {
            infoIcon?.visibility = View.GONE
            scamIcon?.visibility = View.VISIBLE
        } else {
            infoIcon?.visibility = View.VISIBLE
            scamIcon?.visibility = View.GONE
        }

        attachOverlay(view)
    }

    private fun showScamAlertOverlay(data: OverlayData) {
        val view = LayoutInflater.from(this).inflate(R.layout.view_overlay_scam_alert, null)
        view.findViewById<TextView>(R.id.tvScamNumber).text = data.number
        view.findViewById<TextView>(R.id.tvScamReason).text = data.reason
        view.findViewById<View>(R.id.btnOverlayClose)?.setOnClickListener {
            dismissOverlay()
            stopSelf()
        }
        attachOverlay(view)
    }

    private fun attachOverlay(view: View) {
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
            gravity = Gravity.CENTER
        }
        val added = runCatching { windowManager.addView(view, params) }
            .onSuccess { overlayView = view }
            .onFailure {
                if (overlayRetryAttempts == 0) {
                    Log.w("IncomingCallOverlay", "Overlay attach failed", it)
                }
            }
            .isSuccess
        if (added) {
            cancelOverlayRetry()
        } else {
            overlayView = null
            scheduleOverlayRetry()
        }
    }

    private fun isForcedScammer(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        return digits == "0616581564" || digits == "66616581564"
    }

    private fun dismissOverlay() {
        overlayController.dismiss(force = true)
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
        }
        overlayView = null
    }

    @Suppress("DEPRECATION")
    private fun startCallStateListener() {
        if (phoneStateListener != null) return
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> endOverlaySession()
                    TelephonyManager.CALL_STATE_RINGING,
                    TelephonyManager.CALL_STATE_OFFHOOK -> updateNumberIfNeeded(phoneNumber)
                }
            }
        }
        runCatching {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopCallStateListener() {
        phoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
        phoneStateListener = null
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun startForegroundSafe(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                return
            } catch (_: SecurityException) {
                // Fall through to legacy startForeground.
            }
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun cacheProvidedExtras(intent: Intent?) {
        providedName = intent?.getStringExtra(EXTRA_DISPLAY_NAME)
        providedRisk = intent?.getStringExtra(EXTRA_RISK_LEVEL)
            ?.let { runCatching { RiskLevel.valueOf(it) }.getOrNull() }
        providedReason = intent?.getStringExtra(EXTRA_REASON)
        providedCarrier = intent?.getStringExtra(EXTRA_CARRIER)
        providedRegion = intent?.getStringExtra(EXTRA_REGION)
        providedReportCount = intent?.getIntExtra(EXTRA_REPORT_COUNT, 0) ?: 0
    }

    private fun updateNumberIfNeeded(phoneNumber: String?) {
        val candidate = pickUsableNumber(phoneNumber, LastCallStore.get()?.number)
        if (candidate.isBlank()) return
        if (numbersMatch(candidate, currentNumber)) return
        currentNumber = candidate
        showInitialOverlay(candidate)
        maybeEnrichWithNumberCheck(candidate)
    }

    private fun maybeEnrichWithNumberCheck(number: String) {
        val digits = number.filter { it.isDigit() }
        if (digits.isBlank() || digits == lastEnrichedDigits) return
        lastEnrichedDigits = digits
        enrichWithNumberCheck(number)
    }

    private fun pickUsableNumber(primary: String?, fallback: String?): String {
        return when {
            isUsableNumber(primary) -> primary!!.trim()
            isUsableNumber(fallback) -> fallback!!.trim()
            else -> ""
        }
    }

    private fun isUsableNumber(number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        return number.any { it.isDigit() }
    }

    private fun numbersMatch(a: String?, b: String?): Boolean {
        val aDigits = a?.filter { it.isDigit() }.orEmpty()
        val bDigits = b?.filter { it.isDigit() }.orEmpty()
        if (aDigits.isNotBlank() && bDigits.isNotBlank()) {
            return aDigits == bDigits
        }
        return a?.trim() == b?.trim()
    }

    data class OverlayData(
        val name: String,
        val number: String,
        val riskLevel: RiskLevel,
        val reason: String,
        val carrier: String? = null,
        val region: String? = null,
        val reportCount: Int = 0
    )

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_SHOW = "com.sentinel.ai.ACTION_INCOMING_OVERLAY_SHOW"
        private const val ACTION_DISMISS = "com.sentinel.ai.ACTION_INCOMING_OVERLAY_DISMISS"
        private const val EXTRA_NUMBER = "extra_number"
        private const val EXTRA_DISPLAY_NAME = "extra_display_name"
        private const val EXTRA_RISK_LEVEL = "extra_risk_level"
        private const val EXTRA_REASON = "extra_reason"
        private const val EXTRA_CARRIER = "extra_carrier"
        private const val EXTRA_REGION = "extra_region"
        private const val EXTRA_REPORT_COUNT = "extra_report_count"
        private const val SCAM_ALERT_DELAY_MS = 3_000L
        private const val OVERLAY_RETRY_DELAY_MS = 350L
        private const val OVERLAY_RETRY_MAX = 30

        fun show(
            context: Context,
            number: String,
            displayName: String? = null,
            riskLevel: RiskLevel? = null,
            reason: String? = null,
            carrier: String? = null,
            region: String? = null,
            reportCount: Int = 0
        ) {
            val intent = Intent(context, IncomingCallOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_NUMBER, number)
                displayName?.let { putExtra(EXTRA_DISPLAY_NAME, it) }
                riskLevel?.let { putExtra(EXTRA_RISK_LEVEL, it.name) }
                reason?.let { putExtra(EXTRA_REASON, it) }
                carrier?.let { putExtra(EXTRA_CARRIER, it) }
                region?.let { putExtra(EXTRA_REGION, it) }
                putExtra(EXTRA_REPORT_COUNT, reportCount)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun dismiss(context: Context) {
            val intent = Intent(context, IncomingCallOverlayService::class.java).apply {
                action = ACTION_DISMISS
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private fun scheduleOverlayRetry() {
        if (!overlaySessionActive) return
        if (overlayRetryAttempts >= OVERLAY_RETRY_MAX) return
        overlayRetryAttempts += 1
        overlayRetryJob?.cancel()
        overlayRetryJob = serviceScope.launch {
            delay(OVERLAY_RETRY_DELAY_MS)
            if (!overlaySessionActive || overlayView != null) return@launch
            lastOverlayData?.let { renderOverlay(it, resetRetry = false) }
        }
    }

    private fun cancelOverlayRetry() {
        overlayRetryJob?.cancel()
        overlayRetryJob = null
        overlayRetryAttempts = 0
    }

    private fun endOverlaySession() {
        overlaySessionActive = false
        cancelOverlayRetry()
        dismissOverlay()
        stopSelf()
    }
}

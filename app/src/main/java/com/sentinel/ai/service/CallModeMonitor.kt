package com.sentinel.ai.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.security.ScamKeywordMatcher
import com.sentinel.ai.utils.CallTtsController
import com.sentinel.ai.utils.OverlayController
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.SpeechTestController
import com.sentinel.ai.utils.ContactLookup
import com.sentinel.ai.utils.KnownNumberRepository
import com.sentinel.ai.utils.LastCallStore
import com.sentinel.ai.R
import android.util.Log

/**
 * Watches call state: uses playback capture + Whisper (when enabled) or falls back to mic + TTS.
 */
class CallModeMonitor(private val context: Context) {   
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val speechTester = SpeechTestController(context)
    private val tts = CallTtsController(context)
    private val overlay by lazy { OverlayController(context) }
    private val handler = Handler(Looper.getMainLooper())

    private var phoneStateListener: PhoneStateListener? = null
    private var micRunning = false

    @Suppress("DEPRECATION")
    fun start() {
        if (phoneStateListener != null) return
        if (!PermissionUtils.hasPhoneStatePermission(context)) {
            GuardianEventStore.addEvent(
                GuardianEvent(
                    source = "Call monitor",
                    content = "Phone state permission missing. Call listening disabled.",
                    score = 0,
                    riskLevel = RiskLevel.SAFE
                )
            )
            return
        }
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING,
                    TelephonyManager.CALL_STATE_OFFHOOK -> enterCallMode(phoneNumber)
                    TelephonyManager.CALL_STATE_IDLE -> exitCallMode()
                }
            }
        }
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (se: SecurityException) {
            GuardianEventStore.addEvent(
                GuardianEvent(
                    source = "Call monitor",
                    content = "Cannot start call listener: ${se.message}",
                    score = 0,
                    riskLevel = RiskLevel.SAFE
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        exitCallMode()
        phoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
        phoneStateListener = null
    }

    private fun enterCallMode(number: String?) {
        if (micRunning) return
        if (!android.provider.Settings.canDrawOverlays(context)) {
            GuardianEventStore.addEvent(
                GuardianEvent(
                    source = "Call monitor",
                    content = "Overlay permission missing. Cannot show UI.",
                    score = 0,
                    riskLevel = RiskLevel.SAFE
                )
            )
            return
        }

        try {
            micRunning = true
            GuardianEventStore.addEvent(
                GuardianEvent(
                    source = "Call monitor",
                    content = "Call detected (in/out) from ${number ?: "unknown"}",
                    score = 0,
                    riskLevel = RiskLevel.SAFE
                )
            )

            // Show caller overlay right away using contacts/known numbers
            try {
                val fallbackNumber = LastCallStore.get()?.number
                val safeNumber = number?.takeIf { it.isNotBlank() } ?: fallbackNumber ?: context.getString(R.string.common_unknown)
                val contactName = ContactLookup.getContactName(context, safeNumber)
                val known = KnownNumberRepository.lookup(safeNumber) ?: KnownNumberRepository.heuristic(safeNumber)
                val riskLevel = known?.riskLevel ?: RiskLevel.SAFE
                val displayName = contactName ?: known?.displayName ?: context.getString(R.string.common_unknown)
                val reason = known?.reason ?: context.getString(R.string.overlay_no_reports)
                overlay.showCallerInfo(
                    name = displayName,
                    number = safeNumber,
                    riskLevel = riskLevel,
                    reason = reason,
                    isOutgoing = stateIsOutgoing(),
                    dismissOnCallState = false,
                    allowGatekeeperDismiss = false,
                    autoDismissMs = 0L,
                    bypassGate = true
                )
            } catch (_: Exception) { }

            if (!PermissionUtils.hasMicPermission(context)) {
                GuardianEventStore.addEvent(
                    GuardianEvent(
                        source = "Call monitor",
                        content = "Mic permission missing. Overlay only; call listening disabled.",
                        score = 0,
                        riskLevel = RiskLevel.SAFE
                    )
                )
                try {
                    tts.speak("Microphone permission missing. Overlay only.", flush = true)
                } catch (_: Exception) { /* ignore */ }
                return
            }

            // เดิมทีตรงนี้จะลองใช้โหมด playback capture + MediaProjection (ขอแชร์หน้าจอ)
            // ซึ่งทำให้ขึ้น popup ขอสิทธิ์แชร์จอทุกครั้งที่มีสายเข้า/ออก
            // ตอนนี้ปิดไว้ชั่วคราว แล้วใช้เฉพาะโหมด mic + overlay แทน
            runMicFallback()
        } catch (e: Exception) {
            Log.e(TAG, "enterCallMode failed", e)
            micRunning = false
            try { overlay.dismiss() } catch (_: Exception) { }
        }
    }

    private fun runMicFallback() {
        try {
            overlay.showLiveTranscript("Listening...")
        } catch (e: Exception) {
            micRunning = false
            return
        }
        try { tts.startKeepAliveLoop("Call monitoring active. Text to speech is running.") } catch (_: Exception) { }
        try {
            speechTester.listenContinuously(
                onResult = { handleTranscript(it) },
                onError = { handleError(it) },
                onPartial = { partial ->
                    if (partial.isNotBlank() && partial != "...") {
                        try { overlay.updateLiveTranscript(partial) } catch (_: Exception) { }
                        val m = ScamKeywordMatcher.get(context).match(partial)
                        if (m != null) {
                            try { overlay.updateLiveTranscriptRisk(RiskLevel.CRITICAL, m.scenarioName) } catch (_: Exception) { }
                            GuardianEventStore.addEvent(GuardianEvent(source = "Scam keyword", content = "${m.scenarioName}: ${m.matchedKeyword} | $partial", score = 80, riskLevel = RiskLevel.CRITICAL))
                        }
                    }
                },
                languageTag = PREFERRED_LANG
            )
        } catch (e: Exception) {
            handleError("Start listen failed: ${e.message}")
            micRunning = false
            try { overlay.dismiss() } catch (_: Exception) { }
        }
    }

    private fun handleTranscript(text: String) {
        GuardianEventStore.addEvent(
            GuardianEvent(
                source = "Call mic",
                content = text,
                score = 0,
                riskLevel = RiskLevel.SAFE
            )
        )
        overlay.updateLiveTranscript(text)
        val m = ScamKeywordMatcher.get(context).match(text)
        if (m != null) {
            try { overlay.updateLiveTranscriptRisk(RiskLevel.CRITICAL, m.scenarioName) } catch (_: Exception) { }
            GuardianEventStore.addEvent(GuardianEvent(source = "Scam keyword", content = "${m.scenarioName}: ${m.matchedKeyword} | $text", score = 80, riskLevel = RiskLevel.CRITICAL))
        }
    }

    private fun handleError(err: String) {
        GuardianEventStore.addEvent(
            GuardianEvent(
                source = "Call mic",
                content = "Mic error: $err",
                score = 0,
                riskLevel = RiskLevel.SAFE
            )
        )
        overlay.updateLiveTranscript("Mic error: $err")
    }

    private fun exitCallMode() {
        if (!micRunning) {
            tts.stopKeepAliveLoop()
            overlay.dismiss()
            return
        }
        micRunning = false
        speechTester.stopContinuous()
        tts.stopKeepAliveLoop()
        overlay.dismiss()
    }

    fun destroy() {
        stop()
        speechTester.destroy()
        tts.shutdown()
    }

    private fun stateIsOutgoing(): Boolean {
        return try {
            // TelephonyManager CALL_STATE_OFFHOOK is used for both, but best guess: outgoing if last state was OFFHOOK and we got an intent ACTION_NEW_OUTGOING elsewhere.
            false
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "CallModeMonitor"
        private const val PREFERRED_LANG = "th-TH"
    }
}

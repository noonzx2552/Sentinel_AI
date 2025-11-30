package com.sentinel.ai.service

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.sentinel.ai.ai.RiskScoring
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.utils.CallTtsController
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.SpeechTestController

/**
 * Watches call state and keeps the mic + TTS alive while a call is active.
 */
class CallModeMonitor(private val context: Context) {
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val speechTester = SpeechTestController(context)
    private val tts = CallTtsController(context)
    private val riskScoring = RiskScoring()

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
        if (!PermissionUtils.hasMicPermission(context)) {
            GuardianEventStore.addEvent(
                GuardianEvent(
                    source = "Call monitor",
                    content = "Mic permission missing. Cannot listen to the call.",
                    score = 0,
                    riskLevel = RiskLevel.SAFE
                )
            )
            tts.speak("Microphone permission missing. Call monitoring cannot start.", flush = true)
            return
        }
        micRunning = true
        GuardianEventStore.addEvent(
            GuardianEvent(
                source = "Call monitor",
                content = "Incoming call detected from ${number ?: "unknown"}",
                score = 0,
                riskLevel = RiskLevel.SAFE
            )
        )
        tts.startKeepAliveLoop("Call monitoring active. Text to speech is running.")
        speechTester.listenContinuously(
            onResult = { handleTranscript(it) },
            onError = { handleError(it) },
            onPartial = {},
            languageTag = PREFERRED_LANG
        )
    }

    private fun handleTranscript(text: String) {
        val risk = riskScoring.score(text)
        val level = toRiskLevel(risk.score)
        GuardianEventStore.addEvent(
            GuardianEvent(
                source = "Call mic",
                content = text,
                score = risk.score,
                riskLevel = level
            )
        )
        if (level != RiskLevel.SAFE) {
            tts.speak("Warning level ${level.name.lowercase()} detected.", flush = true)
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
    }

    private fun exitCallMode() {
        if (!micRunning) {
            tts.stopKeepAliveLoop()
            return
        }
        micRunning = false
        speechTester.stopContinuous()
        tts.stopKeepAliveLoop()
    }

    fun destroy() {
        stop()
        speechTester.destroy()
        tts.shutdown()
    }

    private fun toRiskLevel(score: Int): RiskLevel = when {
        score >= 80 -> RiskLevel.CRITICAL
        score in 40..79 -> RiskLevel.WARNING
        else -> RiskLevel.SAFE
    }

    companion object {
        private const val PREFERRED_LANG = "th-TH"
    }
}

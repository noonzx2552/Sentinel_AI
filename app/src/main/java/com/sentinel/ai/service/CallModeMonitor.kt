package com.sentinel.ai.service

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.utils.CallTtsController
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.OverlayController
import com.sentinel.ai.utils.SpeechTestController

/**
 * Watches call state and keeps the mic + TTS alive while a call is active.
 */
class CallModeMonitor(private val context: Context) {
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val speechTester = SpeechTestController(context)
    private val tts = CallTtsController(context)
    private val overlay by lazy { OverlayController(context) }

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
            try {
                tts.speak("Microphone permission missing. Call monitoring cannot start.", flush = true)
            } catch (e: Exception) {
                // ignore tts error
            }
            return
        }
        
        // Safety check for Overlay permission
        if (!android.provider.Settings.canDrawOverlays(context)) {
             GuardianEventStore.addEvent(
                GuardianEvent(
                    source = "Call monitor",
                    content = "Overlay permission missing. Cannot show UI.",
                    score = 0,
                    riskLevel = RiskLevel.SAFE
                )
            )
            // Even if overlay is missing, we might still want to record audio? 
            // Probably not safely without UI feedback. Let's abort to be safe and avoid crash.
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
        
        try {
            overlay.showLiveTranscript("Listening...")
        } catch (e: Exception) {
            micRunning = false
            return
        }

        try {
            tts.startKeepAliveLoop("Call monitoring active. Text to speech is running.")
        } catch (e: Exception) {
            // ignore
        }

        try {
            speechTester.listenContinuously(
                onResult = { handleTranscript(it) },
                onError = { handleError(it) },
                onPartial = { partial ->
                    if (partial.isNotBlank() && partial != "...") {
                        try {
                            overlay.updateLiveTranscript(partial)
                        } catch (e: Exception) {
                            // ignore UI update error
                        }
                    }
                },
                languageTag = PREFERRED_LANG
            )
        } catch (e: Exception) {
            handleError("Start listen failed: ${e.message}")
            micRunning = false
            try {
                overlay.dismiss()
            } catch (ignore: Exception) {}
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

    companion object {
        private const val PREFERRED_LANG = "th-TH"
    }
}

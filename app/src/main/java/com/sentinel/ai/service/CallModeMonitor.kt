package com.sentinel.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.sentinel.ai.ai.WhisperCppSttClient
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.ui.CallMediaProjectionActivity
import com.sentinel.ai.utils.CallTtsController
import com.sentinel.ai.utils.NetworkUtils
import com.sentinel.ai.utils.OverlayController
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.ProtectionPrefs
import com.sentinel.ai.utils.SpeechTestController

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
    private var broadcastReceiver: BroadcastReceiver? = null
    private val fallbackRunnable = Runnable { onPlaybackFallbackTimeout() }

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
            } catch (e: Exception) { /* ignore */ }
            return
        }
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

        micRunning = true
        GuardianEventStore.addEvent(
            GuardianEvent(
                source = "Call monitor",
                content = "Incoming call detected from ${number ?: "unknown"}",
                score = 0,
                riskLevel = RiskLevel.SAFE
            )
        )

        val usePlayback = ProtectionPrefs.useCallPlaybackCapture(context) &&
            WhisperCppSttClient.isConfigured() &&
            NetworkUtils.isOnline(context)

        if (usePlayback) {
            try {
                overlay.showLiveTranscript("Preparing...")
            } catch (e: Exception) {
                micRunning = false
                return
            }
            val filter = IntentFilter().apply {
                addAction(CallPlaybackCaptureService.ACTION_CALL_PLAYBACK_CAPTURE_STARTED)
                addAction(CallPlaybackCaptureService.ACTION_CALL_CAPTURE_FALLBACK_MIC)
            }
            broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, i: Intent?) {
                    when (i?.action) {
                        CallPlaybackCaptureService.ACTION_CALL_PLAYBACK_CAPTURE_STARTED -> {
                            handler.removeCallbacks(fallbackRunnable)
                            unregisterPlaybackReceiver()
                            try { overlay.dismiss() } catch (_: Exception) { }
                            // Service will show "Listening..." and handle transcript
                        }
                        CallPlaybackCaptureService.ACTION_CALL_CAPTURE_FALLBACK_MIC -> {
                            handler.removeCallbacks(fallbackRunnable)
                            unregisterPlaybackReceiver()
                            runMicFallback()
                        }
                    }
                }
            }
            @Suppress("DEPRECATION")
            context.registerReceiver(
                broadcastReceiver,
                filter,
                if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
            )

            context.startActivity(
                Intent(context, CallMediaProjectionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            handler.postDelayed(fallbackRunnable, FALLBACK_DELAY_MS)
        } else {
            runMicFallback()
        }
    }

    private fun unregisterPlaybackReceiver() {
        try {
            broadcastReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) { }
        broadcastReceiver = null
    }

    private fun onPlaybackFallbackTimeout() {
        if (CallPlaybackCaptureService.isServiceRunning) return
        unregisterPlaybackReceiver()
        runMicFallback()
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
        handler.removeCallbacks(fallbackRunnable)
        unregisterPlaybackReceiver()
        CallPlaybackCaptureService.stop(context)
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
        private const val FALLBACK_DELAY_MS = 15_000L
    }
}

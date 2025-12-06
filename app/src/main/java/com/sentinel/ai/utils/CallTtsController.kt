package com.sentinel.ai.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Simple TTS helper that can keep speaking in the background while call mode is active.
 */
class CallTtsController(
    context: Context,
    private val preferredLocale: Locale = Locale("th", "TH") // default to Thai, fallback below
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var ready = false
    private var initInProgress = true
    private var keepAliveRunnable: Runnable? = null
    private val pendingQueue = mutableListOf<Pair<String, Boolean>>()

    override fun onInit(status: Int) {
        initInProgress = false
        if (status != TextToSpeech.SUCCESS) {
            Log.w("CallTtsController", "TTS init failed (status=$status); will re-init on next speak.")
            ready = false
            return
        }

        // Try the preferred locale first, then fall back to device default, then US.
        ready = setLanguageWithFallback()
        if (!ready) {
            Log.w("CallTtsController", "No supported TTS language found; queuing requests until engine is available.")
            return
        }

        flushPending()
    }

    fun speak(text: String, flush: Boolean = false) {
        val engine = ensureEngine() ?: return
        if (!ready) { // engine still initializing or missing language data
            pendingQueue.add(text to flush)
            return
        }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, "sentinel_call_tts")
    }

    fun startKeepAliveLoop(message: String, intervalMs: Long = 10000L) {
        if (keepAliveRunnable == null) {
            keepAliveRunnable = object : Runnable {
                override fun run() {
                    if (ready) {
                        speak(message, flush = false)
                    }
                    handler.postDelayed(this, intervalMs)
                }
            }
        }
        handler.post(keepAliveRunnable!!)
    }

    fun stopKeepAliveLoop() {
        keepAliveRunnable?.let { handler.removeCallbacks(it) }
        keepAliveRunnable = null
        tts?.stop()
    }

    fun shutdown() {
        stopKeepAliveLoop()
        tts?.shutdown()
        tts = null
        ready = false
        initInProgress = false
        pendingQueue.clear()
    }

    private fun flushPending() {
        if (!ready) return
        if (pendingQueue.isEmpty()) return
        val copy = pendingQueue.toList()
        pendingQueue.clear()
        copy.forEach { (text, flush) -> speak(text, flush) }
    }

    private fun ensureEngine(): TextToSpeech? {
        if ((tts == null || !ready) && !initInProgress) {
            initInProgress = true
            tts?.shutdown()
            tts = TextToSpeech(appContext, this)
        }
        return tts
    }

    private fun setLanguageWithFallback(): Boolean {
        val engine = tts ?: return false

        val candidates = listOf(
            preferredLocale,
            Locale.getDefault(),
            Locale.US
        )

        candidates.forEach { locale ->
            val result = engine.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setSpeechRate(1.0f)
                engine.setPitch(1.0f)
                Log.d("CallTtsController", "TTS language set to ${locale.toLanguageTag()}")
                return true
            }
        }

        return false
    }
}

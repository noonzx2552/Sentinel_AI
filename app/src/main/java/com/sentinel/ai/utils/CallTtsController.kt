package com.sentinel.ai.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Simple TTS helper that can keep speaking in the background while call mode is active.
 */
class CallTtsController(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var ready = false
    private var keepAliveRunnable: Runnable? = null
    private val pendingQueue = mutableListOf<Pair<String, Boolean>>()

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale.US
            flushPending()
        }
    }

    fun speak(text: String, flush: Boolean = false) {
        val engine = tts ?: return
        if (!ready) {
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
        pendingQueue.clear()
    }

    private fun flushPending() {
        if (!ready) return
        if (pendingQueue.isEmpty()) return
        val copy = pendingQueue.toList()
        pendingQueue.clear()
        copy.forEach { (text, flush) -> speak(text, flush) }
    }
}

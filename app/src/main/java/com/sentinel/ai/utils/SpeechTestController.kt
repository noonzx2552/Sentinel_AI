package com.sentinel.ai.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Lightweight helper to capture one-shot speech from the mic for testing purposes.
 * Uses on-device SpeechRecognizer (network may be required on some devices).
 */
class SpeechTestController(context: Context) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = SpeechRecognizer.createSpeechRecognizer(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private var continuous = false

    fun listenOnce(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onPartial: (String) -> Unit = {},
        languageTag: String? = null
    ) {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also { recognizer = it }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            languageTag?.let {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, it)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            } ?: putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // Treat "no match" as silence, keep loop alive silently.
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    onPartial("...")
                } else {
                    onError("STT error: $error")
                    // Re-create recognizer on fatal errors to keep loop healthy.
                    recognizer?.destroy()
                    recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) {
                    onError("No speech recognized")
                } else {
                    onResult(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    onPartial(text)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intent)
    }

    /**
     * Keeps the mic open by restarting recognition after each result/error
     * until stopContinuous() is called.
     */
    fun listenContinuously(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onPartial: (String) -> Unit = {},
        languageTag: String? = null,
        restartDelayMs: Long = 350L
    ) {
        continuous = true
        fun startRound() {
            if (!continuous) return
            listenOnce(
                onResult = {
                    onResult(it)
                    if (continuous) handler.postDelayed({ startRound() }, restartDelayMs)
                },
                onError = {
                    onError(it)
                    if (continuous) handler.postDelayed({ startRound() }, restartDelayMs)
                },
                onPartial = onPartial,
                languageTag = languageTag
            )
        }
        startRound()
    }

    fun stopContinuous() {
        continuous = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
    }

    fun destroy() {
        stopContinuous()
        recognizer?.destroy()
        recognizer = null
    }
}

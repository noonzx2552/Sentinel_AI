package com.sentinel.ai.service

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.sentinel.ai.BuildConfig
import com.sentinel.ai.R
import com.sentinel.ai.SentinelApp
import com.sentinel.ai.ai.RiskScoring
import com.sentinel.ai.ai.SttResult
import com.sentinel.ai.model.CallerOverlayUiState
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.ProtectionMode
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.security.NumberChecker
import com.sentinel.ai.security.SafetyLevel
import com.sentinel.ai.security.ScamKeywordMatcher
import com.sentinel.ai.utils.ContactLookup
import com.sentinel.ai.utils.KnownNumberRepository
import com.sentinel.ai.utils.MediaProjectionHolder
import com.sentinel.ai.utils.MediaProjectionStore
import com.sentinel.ai.utils.OverlayController
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.SpeechTestController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class CallProtectionOrchestrator private constructor(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val overlay = OverlayController(appContext)
    private val speech = SpeechTestController(appContext)
    private val riskScoring = RiskScoring()

    private var currentState: CallerOverlayUiState? = null
    private var audioEnabled = true
    private var micRunning = false

    fun onCallStarted(phoneNumber: String?) {
        val number = phoneNumber?.takeIf { it.any(Char::isDigit) } ?: ""
        val contactName = ContactLookup.getContactName(appContext, number)
        val known = KnownNumberRepository.lookup(number) ?: KnownNumberRepository.heuristic(number)
        val reasons = mutableListOf<String>()
        val tags = mutableListOf<String>()
        if (contactName.isNullOrBlank()) {
            reasons.add(appContext.getString(R.string.overlay_reason_unknown_number))
            tags.add(appContext.getString(R.string.overlay_tag_unknown_number))
        } else {
            tags.add(appContext.getString(R.string.overlay_tag_contact))
        }
        known?.reason?.takeIf { it.isNotBlank() }?.let { reasons.add(it) }

        val initialRisk = known?.riskLevel ?: if (contactName.isNullOrBlank()) RiskLevel.WARNING else RiskLevel.SAFE
        val initialScore = when (initialRisk) {
            RiskLevel.SAFE -> if (contactName.isNullOrBlank()) 38 else 12
            RiskLevel.WARNING -> 48
            RiskLevel.CRITICAL -> 84
        }
        val mode = resolveInitialMode()
        val modeReason = when (mode) {
            ProtectionMode.PLAYBACK_CAPTURE -> null
            ProtectionMode.MIC_FALLBACK -> appContext.getString(R.string.overlay_reason_mic_fallback)
            ProtectionMode.NO_AUDIO -> appContext.getString(R.string.overlay_reason_audio_unavailable)
            ProtectionMode.NUMBER_ONLY -> appContext.getString(R.string.overlay_mode_number_only)
            else -> null
        }
        modeReason?.let { reasons.add(it) }

        val state = CallerOverlayUiState(
            phoneNumber = number,
            displayName = contactName ?: known?.displayName,
            riskLevel = initialRisk,
            riskScore = initialScore,
            reasons = reasons.distinct().ifEmpty { listOf(appContext.getString(R.string.overlay_reason_default_safe)) },
            sourceTags = tags.distinct().ifEmpty { listOf(appContext.getString(R.string.overlay_tag_number_check)) },
            protectionMode = mode,
            liveTranscript = null,
            isExpanded = initialRisk != RiskLevel.SAFE
        )
        publish(state, force = true)
        saveEvent("Call protection", state.reasons.joinToString(" | "), state)
        enrichNumber(number)
        startBestAudioMode()
    }

    fun onPlaybackFailed(reason: String? = null) {
        if (!audioEnabled) return
        val state = currentState ?: return
        val reasons = (state.reasons + listOfNotNull(reason, appContext.getString(R.string.overlay_reason_mic_fallback))).distinct()
        publish(
            state.copy(
                protectionMode = if (PermissionUtils.hasMicPermission(appContext)) ProtectionMode.MIC_FALLBACK else ProtectionMode.NUMBER_ONLY,
                reasons = reasons,
                sourceTags = (state.sourceTags + appContext.getString(R.string.overlay_tag_live_audio)).distinct(),
                isExpanded = true,
                timestamp = System.currentTimeMillis()
            ),
            force = true
        )
        startMicFallback()
    }

    fun onAudioTranscript(text: String, sttResult: SttResult? = null) {
        if (text.isBlank()) return
        val current = currentState ?: return
        val keyword = ScamKeywordMatcher.get(appContext).match(text)
        val decision = if (keyword != null) {
            com.sentinel.ai.ai.RiskDecision(
                score = 88,
                riskLevel = RiskLevel.CRITICAL,
                reasons = listOf("พบคำว่า ${keyword.matchedKeyword}", keyword.scenarioName),
                sourceTags = listOf(appContext.getString(R.string.overlay_tag_keyword), appContext.getString(R.string.overlay_tag_live_audio))
            )
        } else {
            riskScoring.decide(text, extraSourceTags = listOf(appContext.getString(R.string.overlay_tag_live_audio)))
        }
        val nextLevel = maxRisk(current.riskLevel, decision.riskLevel)
        val nextScore = maxOf(current.riskScore, decision.score)
        val next = current.copy(
            riskLevel = nextLevel,
            riskScore = nextScore,
            reasons = (decision.reasons + current.reasons).distinct(),
            sourceTags = (decision.sourceTags + current.sourceTags).distinct(),
            liveTranscript = text.take(160),
            isExpanded = nextLevel != RiskLevel.SAFE,
            timestamp = System.currentTimeMillis()
        )
        publish(next, force = nextLevel == RiskLevel.CRITICAL)
        saveEvent(
            source = sttResult?.engineName ?: "Call audio",
            content = text.take(220),
            state = next,
            sttResult = sttResult
        )
    }

    fun onMicError(error: String) {
        val state = currentState ?: return
        publish(
            state.copy(
                protectionMode = ProtectionMode.NUMBER_ONLY,
                reasons = (state.reasons + error + appContext.getString(R.string.overlay_reason_audio_unavailable)).distinct(),
                isExpanded = true,
                timestamp = System.currentTimeMillis()
            ),
            force = true
        )
    }

    fun onCallEnded() {
        runCatching { CallPlaybackCaptureService.stop(appContext) }
        runCatching { speech.stopContinuous() }
        micRunning = false
        overlay.dismiss(force = true)
        currentState = null
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
        if (!enabled) {
            runCatching { CallPlaybackCaptureService.stop(appContext) }
            runCatching { speech.stopContinuous() }
            micRunning = false
            currentState?.let {
                publish(
                    it.copy(
                        protectionMode = ProtectionMode.NUMBER_ONLY,
                        reasons = (it.reasons + appContext.getString(R.string.overlay_mode_number_only)).distinct(),
                        timestamp = System.currentTimeMillis()
                    ),
                    force = true
                )
            }
        } else {
            startBestAudioMode()
        }
    }

    fun toggleAudio() {
        setAudioEnabled(!audioEnabled)
    }

    fun reportCurrentNumber() {
        val state = currentState ?: return
        saveEvent(
            source = "User report",
            content = "User reported caller ${state.phoneNumber}",
            state = state.copy(
                reasons = (listOf(appContext.getString(R.string.overlay_report_saved)) + state.reasons).distinct(),
                sourceTags = (state.sourceTags + appContext.getString(R.string.overlay_tag_number_check)).distinct(),
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun resolveInitialMode(): ProtectionMode {
        return when {
            MediaProjectionHolder.isReady() || MediaProjectionStore.get() != null -> ProtectionMode.PLAYBACK_CAPTURE
            PermissionUtils.hasMicPermission(appContext) -> ProtectionMode.MIC_FALLBACK
            else -> ProtectionMode.NUMBER_ONLY
        }
    }

    private fun startBestAudioMode() {
        if (!audioEnabled) return
        when (resolveInitialMode()) {
            ProtectionMode.PLAYBACK_CAPTURE -> {
                updateMode(ProtectionMode.PLAYBACK_CAPTURE)
                runCatching { CallPlaybackCaptureService.startHeld(appContext) }
                    .onFailure { onPlaybackFailed(it.message) }
            }
            ProtectionMode.MIC_FALLBACK -> startMicFallback()
            else -> updateMode(ProtectionMode.NUMBER_ONLY)
        }
    }

    private fun startMicFallback() {
        if (micRunning || !audioEnabled) return
        if (!PermissionUtils.hasMicPermission(appContext)) {
            updateMode(ProtectionMode.NUMBER_ONLY, appContext.getString(R.string.overlay_reason_mic_missing))
            return
        }
        micRunning = true
        updateMode(ProtectionMode.MIC_FALLBACK, appContext.getString(R.string.overlay_reason_mic_fallback))
        runCatching {
            speech.listenContinuously(
                onResult = { onAudioTranscript(it) },
                onError = {
                    micRunning = false
                    onMicError(it)
                },
                onPartial = { partial ->
                    if (partial.isNotBlank() && partial != "...") onAudioTranscript(partial)
                },
                languageTag = "th-TH"
            )
        }.onFailure {
            micRunning = false
            onMicError(it.message ?: "SpeechRecognizer failed")
        }
    }

    private fun updateMode(mode: ProtectionMode, reason: String? = null) {
        val state = currentState ?: return
        publish(
            state.copy(
                protectionMode = mode,
                reasons = (state.reasons + listOfNotNull(reason)).distinct(),
                timestamp = System.currentTimeMillis()
            ),
            force = true
        )
    }

    private fun enrichNumber(number: String) {
        if (number.isBlank()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { NumberChecker(appContext).check(number) }
                    .onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "Number check failed: ${it.message}") }
                    .getOrNull()
            } ?: return@launch
            val current = currentState ?: return@launch
            val mapped = mapSafetyToRisk(result.status, result.score, result.reportCount)
            val reasons = mutableListOf<String>()
            reasons.addAll(result.issues)
            if (result.reportCount > 0) {
                reasons.add(appContext.getString(R.string.overlay_reason_blacklist))
                reasons.add(appContext.getString(R.string.checknumber_reports_found_format, result.reportCount))
            }
            val tags = mutableListOf(appContext.getString(R.string.overlay_tag_number_check))
            if (result.reportCount > 0) tags.add(appContext.getString(R.string.overlay_tag_blacklist))
            val next = current.copy(
                displayName = current.displayName,
                riskLevel = maxRisk(current.riskLevel, mapped),
                riskScore = maxOf(current.riskScore, (100 - result.score).coerceIn(0, 100)),
                reasons = (reasons + current.reasons).distinct().ifEmpty { current.reasons },
                sourceTags = (tags + current.sourceTags).distinct(),
                isExpanded = mapped != RiskLevel.SAFE || result.reportCount > 0 || current.isExpanded,
                timestamp = System.currentTimeMillis()
            )
            publish(next, force = mapped == RiskLevel.CRITICAL)
            saveEvent("Number check", next.reasons.joinToString(" | "), next)
        }
    }

    private fun mapSafetyToRisk(status: SafetyLevel, safetyScore: Int, reportCount: Int): RiskLevel {
        if (reportCount > 0) return RiskLevel.CRITICAL
        return when (status) {
            SafetyLevel.DANGER -> RiskLevel.CRITICAL
            SafetyLevel.CAUTION -> RiskLevel.WARNING
            SafetyLevel.SAFE -> if (safetyScore >= 75) RiskLevel.SAFE else RiskLevel.WARNING
            SafetyLevel.UNKNOWN -> RiskLevel.WARNING
        }
    }

    private fun publish(state: CallerOverlayUiState, force: Boolean = false) {
        currentState = state
        if (!Settings.canDrawOverlays(appContext)) return
        overlay.showCallerRiskOverlay(state, force)
    }

    private fun saveEvent(source: String, content: String, state: CallerOverlayUiState, sttResult: SttResult? = null) {
        val event = GuardianEvent(
            source = source,
            content = content,
            score = state.riskScore,
            riskLevel = state.riskLevel,
            reasonsJson = JSONArray(state.reasons).toString(),
            sourceTagsJson = JSONArray(state.sourceTags).toString(),
            protectionMode = state.protectionMode.name,
            phoneNumber = state.phoneNumber,
            displayName = state.displayName,
            transcriptSnippet = state.liveTranscript,
            audioMode = state.protectionMode.name,
            confidence = sttResult?.confidence,
            latencyMs = sttResult?.latencyMs
        )
        GuardianEventStore.addEvent(event)
        scope.launch(Dispatchers.IO) {
            runCatching { SentinelApp.instance.database.eventDao().insert(event) }
                .onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "Persist event failed: ${it.message}") }
        }
    }

    private fun maxRisk(a: RiskLevel, b: RiskLevel): RiskLevel {
        return if (weight(b) > weight(a)) b else a
    }

    private fun weight(level: RiskLevel): Int = when (level) {
        RiskLevel.SAFE -> 0
        RiskLevel.WARNING -> 1
        RiskLevel.CRITICAL -> 2
    }

    fun destroy() {
        onCallEnded()
        speech.destroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "CallProtection"
        @Volatile private var instance: CallProtectionOrchestrator? = null

        fun active(context: Context): CallProtectionOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: CallProtectionOrchestrator(context.applicationContext).also { instance = it }
            }
        }
    }
}

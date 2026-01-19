package com.sentinel.ai.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.security.NumberChecker
import com.sentinel.ai.utils.ContactLookup
import com.sentinel.ai.utils.KnownNumberRepository
import com.sentinel.ai.utils.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles outgoing calls to show overlay with number analysis.
 */
class OutgoingCallReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_NEW_OUTGOING_CALL) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: return@launch

                // Lookup contact and known number info
                val contactName = ContactLookup.getContactName(context, number)
                val known = KnownNumberRepository.lookup(number) ?: KnownNumberRepository.heuristic(number)

                val riskLevel = known?.riskLevel ?: RiskLevel.SAFE
                val displayName = contactName ?: known?.displayName ?: "Unknown"
                val reason = known?.reason ?: "Checking number..."
                val score = when (riskLevel) {
                    RiskLevel.SAFE -> 85
                    RiskLevel.WARNING -> 45
                    RiskLevel.CRITICAL -> 15
                }

                // Log event
                GuardianEventStore.addEvent(
                    GuardianEvent(
                        source = "Outgoing call",
                        content = "Calling $displayName ($number)",
                        score = score,
                        riskLevel = riskLevel
                    )
                )

                val overlay = OverlayController(context)
                try {
                    overlay.showCallerInfo(
                        name = displayName,
                        number = number,
                        riskLevel = riskLevel,
                        reason = reason,
                        score = score,
                        isOutgoing = true,
                        reasonOverride = "Searching...",
                        gravity = android.view.Gravity.CENTER
                    )
                } catch (e: Exception) {
                    Log.e("OutgoingCallReceiver", "Failed to show overlay", e)
                }

                // Enrich with external report lookup (Blacklist API)
                val checker = NumberChecker(context)
                runCatching { checker.check(number) }
                    .onSuccess { result ->
                        if (result.carrier != null || result.countryName != null) {
                            GuardianEventStore.addEvent(
                                GuardianEvent(
                                    source = "Caller info",
                                    content = "Provider: ${result.carrier ?: "Unknown"} | Country: ${result.countryName ?: result.region ?: "-"}",
                                    score = result.score,
                                    riskLevel = riskLevel
                                )
                            )
                        }
                        val reportSummary = if (result.reportCount > 0) {
                            result.reportDetails.joinToString(" / ").take(140)
                        } else {
                            null
                        }
                        try {
                            overlay.showCallerInfo(
                                name = displayName,
                                number = number,
                                riskLevel = riskLevel,
                                reason = reason,
                                score = score,
                                isOutgoing = true,
                                carrier = result.carrier,
                                region = result.countryName ?: result.region,
                                reportCount = result.reportCount,
                                reportSummary = reportSummary,
                                gravity = android.view.Gravity.CENTER
                            )
                        } catch (_: Exception) {}
                        if (result.reportCount > 0) {
                            val reportText = "Found ${result.reportCount} reports | ${result.reportDetails.joinToString(" / ").take(140)}"
                            GuardianEventStore.addEvent(
                                GuardianEvent(
                                    source = "BlacklistSeller",
                                    content = reportText,
                                    score = (100 - result.reportCount * 10).coerceIn(0, 100),
                                    riskLevel = RiskLevel.CRITICAL
                                )
                            )
                            // try { overlay.showCritical() } catch (_: Exception) {} // Removed to prevent overwriting card
                        }
                    }
                    .onFailure {
                        Log.w("OutgoingCallReceiver", "Report lookup failed: ${it.message}")
                    }
            } catch (e: Exception) {
                Log.e("OutgoingCallReceiver", "Error processing outgoing call", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

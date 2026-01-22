package com.sentinel.ai.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sentinel.ai.R
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.security.NumberChecker
import com.sentinel.ai.utils.ContactLookup
import com.sentinel.ai.utils.LastCallStore
import com.sentinel.ai.utils.KnownNumberRepository
import com.sentinel.ai.utils.LanguageManager
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.service.IncomingCallOverlayService
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

        // 1. Ensure we use the user's selected language, not the system default
        val appLanguage = LanguageManager.getLanguage(context)
        val localizedContext = LanguageManager.contextForLanguage(context, appLanguage)

        val pendingResult = goAsync()
        scope.launch {
            // 0. Basic guards: we need overlay + phone permissions, otherwise nothing will show.
            if (!PermissionUtils.canDrawOverlays(context) || !PermissionUtils.hasPhoneStatePermission(context)) {
                pendingResult.finish()
                return@launch
            }
            try {
                val number = (intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                    ?: intent.data?.schemeSpecificPart
                    ?: "").trim()
                if (number.isBlank()) {
                    IncomingCallOverlayService.show(
                        context = context,
                        number = "",
                        displayName = localizedContext.getString(R.string.common_unknown),
                        riskLevel = RiskLevel.SAFE,
                        reason = localizedContext.getString(R.string.call_status_searching),
                        carrier = null,
                        region = null,
                        reportCount = 0
                    )
                    return@launch
                }
                LastCallStore.setOutgoing(number)

                // Lookup contact and known number info
                val contactName = ContactLookup.getContactName(context, number)
                val known = KnownNumberRepository.lookup(number) ?: KnownNumberRepository.heuristic(number)

                val riskLevel = known?.riskLevel ?: RiskLevel.SAFE
                val displayName = contactName ?: known?.displayName ?: localizedContext.getString(R.string.common_unknown)
                val reason = known?.reason ?: localizedContext.getString(R.string.call_status_checking)
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

                // For consistency with incoming calls, always go through IncomingCallOverlayService.
                // This gives us a single code path for overlays (WindowManager + layouts).
                try {
                    IncomingCallOverlayService.show(
                        context = context,
                        number = number,
                        displayName = displayName,
                        riskLevel = riskLevel,
                        reason = localizedContext.getString(R.string.call_status_searching),
                        carrier = null,
                        region = null,
                        reportCount = 0
                    )
                } catch (e: Exception) {
                    Log.e("OutgoingCallReceiver", "Failed to start outgoing overlay service", e)
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
                        // Localize report summary if possible? The API returns English usually. 
                        // But "Found X reports" part we can localize.
                        val reportSummary = if (result.reportCount > 0) {
                            result.reportDetails.joinToString(" / ").take(140)
                        } else {
                            null
                        }

                        // Rebuild reason with localized base text.
                        val updatedReason = known?.reason ?: localizedContext.getString(R.string.call_status_checking)
                        val enrichedReason = if (reportSummary != null) {
                            "$updatedReason | $reportSummary"
                        } else {
                            updatedReason
                        }

                        // Update overlay through the same foreground service used for incoming calls.
                        try {
                            IncomingCallOverlayService.show(
                                context = context,
                                number = number,
                                displayName = displayName,
                                riskLevel = riskLevel,
                                reason = enrichedReason,
                                carrier = result.carrier,
                                region = result.countryName ?: result.region,
                                reportCount = result.reportCount
                            )
                        } catch (_: Exception) {
                        }
                        
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

package com.sentinel.ai.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.sentinel.ai.utils.LastCallStore
import com.sentinel.ai.service.IncomingCallOverlayService

/**
 * Lightweight fallback to show overlay on incoming calls even if CallScreeningService is not invoked.
 * Requires READ_PHONE_STATE permission.
 */
class IncomingCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
        val number = rawNumber.ifBlank { LastCallStore.get()?.number ?: "" }
        if (number.isNotBlank()) LastCallStore.setIncoming(number)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> IncomingCallOverlayService.show(context, number)
            TelephonyManager.EXTRA_STATE_IDLE -> IncomingCallOverlayService.dismiss(context)
        }
    }
}

package com.sentinel.ai.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sentinel.ai.service.SentinelGuardianService
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.ProtectionPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == "android.intent.action.QUICKBOOT_POWERON") {
            if (ProtectionPrefs.isEnabled(context) && PermissionUtils.allEssentialGranted(context)) {
                SentinelGuardianService.start(context)
            }
        }
    }
}

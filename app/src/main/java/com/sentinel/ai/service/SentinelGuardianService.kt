package com.sentinel.ai.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.sentinel.ai.utils.NotificationHelper

/**
 * Persistent foreground service that keeps Sentinel AI active in the background.
 * All analysis happens on-device; audio/text buffers are ephemeral.
 */
class SentinelGuardianService : Service() {

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var callModeMonitor: CallModeMonitor

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        val notification = notificationHelper.buildGuardianNotification()
        startForeground(NOTIFICATION_ID, notification)
        callModeMonitor = CallModeMonitor(this)
        callModeMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-attach listeners if the system restarts the service.
        callModeMonitor.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        callModeMonitor.destroy()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, SentinelGuardianService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SentinelGuardianService::class.java))
        }
    }
}

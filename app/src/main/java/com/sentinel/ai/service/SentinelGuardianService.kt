package com.sentinel.ai.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
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
        startForegroundSafe(notification)
        callModeMonitor = CallModeMonitor(this)
        callModeMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Re-attach listeners if the system restarts the service.
        callModeMonitor.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        callModeMonitor.destroy()
        super.onDestroy()
    }

    private fun startForegroundSafe(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Limit the FGS type to mic/data-sync to avoid phoneCall restrictions on Android 14.
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            try {
                startForeground(NOTIFICATION_ID, notification, types)
                return
            } catch (se: SecurityException) {
                Log.w(TAG, "startForeground rejected for declared types; falling back. ${se.message}")
            }
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "SentinelGuardianService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.sentinel.ai.ACTION_STOP_GUARDIAN"

        fun start(context: Context) {
            val intent = Intent(context, SentinelGuardianService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SentinelGuardianService::class.java))
        }
    }
}

package com.sentinel.ai.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sentinel.ai.R
import com.sentinel.ai.ui.DashboardActivity
import com.sentinel.ai.service.SentinelGuardianService

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "sentinel_guardian_channel"
        const val CARETAKER_CHANNEL_ID = "sentinel_caretaker_channel"
    }

    fun buildGuardianNotification(): Notification {
        ensureChannel()
        val exitIntent = Intent(context, SentinelGuardianService::class.java).apply {
            action = SentinelGuardianService.ACTION_EXIT
        }
        val exitPendingIntent = PendingIntent.getService(
            context,
            100,
            exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_tap_to_stop))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            // Tap header to exit directly.
            .setContentIntent(exitPendingIntent)
            .build()
    }

    fun sendCaretakerAlert(title: String, body: String) {
        ensureCaretakerChannel()
        val intent = Intent(context, DashboardActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CARETAKER_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(3001, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_desc)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun ensureCaretakerChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CARETAKER_CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CARETAKER_CHANNEL_ID,
                    context.getString(R.string.caretaker_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.caretaker_channel_desc)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}

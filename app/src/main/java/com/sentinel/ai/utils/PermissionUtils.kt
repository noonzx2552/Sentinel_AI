package com.sentinel.ai.utils

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sentinel.ai.service.SentinelAccessibilityService

object PermissionUtils {

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasReadContacts(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun hasCallLogPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun requestMicPermission(activity: Activity, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), requestCode)
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, SentinelAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.split(":")?.any { it.equals(expected, ignoreCase = true) } == true
    }

    fun openAccessibilitySettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun isCallScreeningRoleGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        } else {
            true
        }
    }

    fun requestCallScreeningRole(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(Context.ROLE_SERVICE) as RoleManager
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            activity.startActivityForResult(intent, requestCode)
        }
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.areNotificationsEnabled()
        }
    }

    fun hasPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    fun requestNotificationPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
        }
    }

    fun requestPhoneStatePermission(activity: Activity, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_PHONE_STATE), requestCode)
    }

    fun requestContacts(activity: Activity, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CONTACTS), requestCode)
    }

    fun requestCallLogAndSms(activity: Activity, requestCode: Int) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS),
            requestCode
        )
    }

    fun allEssentialGranted(context: Context): Boolean {
        return hasMicPermission(context) &&
            canDrawOverlays(context) &&
            isCallScreeningRoleGranted(context) &&
            isNotificationPermissionGranted(context) &&
            isAccessibilityEnabled(context) &&
            hasPhoneStatePermission(context) &&
            hasCallLogPermission(context) &&
            hasSmsPermission(context) &&
            isBatteryOptimizationIgnored(context)
    }

    /**
     * Bypass / Limited mode: only requires the minimum to run manual scans.
     * Call screening, accessibility, overlay, battery optimization are optional.
     */
    fun hasMinimalGranted(context: Context): Boolean {
        return isNotificationPermissionGranted(context)
    }

    /**
     * Returns how many of the 9 essential permissions are currently granted (0-9).
     */
    fun grantedCount(context: Context): Int {
        var count = 0
        if (hasMicPermission(context)) count++
        if (canDrawOverlays(context)) count++
        if (isCallScreeningRoleGranted(context)) count++
        if (isNotificationPermissionGranted(context)) count++
        if (isAccessibilityEnabled(context)) count++
        if (hasPhoneStatePermission(context)) count++
        if (hasCallLogPermission(context)) count++
        if (hasSmsPermission(context)) count++
        if (isBatteryOptimizationIgnored(context)) count++
        return count
    }

    fun totalPermissionCount(): Int = 9
    
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimization(activity: Activity) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        try {
            activity.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
            )
        }
    }
}

package com.sentinel.ai.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Whitelist gate: only allow Sentinel overlays/features when the foreground package
 * is in the allowed list. Update from accessibility events.
 */
object AllowedAppGate {
    // Default whitelist; user can override via setAllowedPackages().
    private val defaultAllowed = setOf(
        "com.instagram.android",
        "com.facebook.orca", // Messenger
        "com.linecorp.line.android",
        "com.facebook.katana", // Facebook
        "com.whatsapp",
        "com.sentinel.ai", // allow in-app overlays
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.incallui",
        "com.google.android.apps.messaging",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.coloros.numberbook",
        "com.oppo.is",
        "com.android.server.telecom",
        "com.mediatek.incallui",
        "com.sec.android.app.dialertab",
        "com.realme.dialer",
        "com.samsung.android.app.telephonyui",
        "com.samsung.android.contacts",
        "com.android.phone"
    )

    private const val PREFS = "sentinel_allowed_apps"
    private const val KEY_ALLOWED = "packages"

    @Volatile private var allowed = true
    @Volatile private var allowedPackages: Set<String> = defaultAllowed

    fun updateForeground(packageName: String?) {
        allowed = packageName != null && allowedPackages.contains(packageName)
        if (!allowed) {
            OverlayGatekeeper.dismissAll()
        }
    }

    fun isAllowed(): Boolean = allowed

    fun init(context: Context) {
        allowedPackages = loadAllowed(context)
    }

    fun setAllowedPackages(context: Context, packages: Set<String>) {
        allowedPackages = packages.ifEmpty { defaultAllowed }
        prefs(context).edit {
            putStringSet(KEY_ALLOWED, allowedPackages)
        }
    }

    fun allowAllInstalled(context: Context) {
        val pm = context.packageManager
        val pkgs = pm.getInstalledApplications(0).map { it.packageName }.toSet()
        setAllowedPackages(context, pkgs)
    }

    fun getAllowedPackages(): Set<String> = allowedPackages

    private fun loadAllowed(context: Context): Set<String> {
        val stored = prefs(context).getStringSet(KEY_ALLOWED, null)
        return stored?.toSet() ?: defaultAllowed
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

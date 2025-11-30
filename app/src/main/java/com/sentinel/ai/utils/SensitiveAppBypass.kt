package com.sentinel.ai.utils

import com.sentinel.ai.utils.OverlayGatekeeper

/**
 * Tracks sensitive apps (e.g., banking) to disable overlays while they are foreground.
 */
object SensitiveAppBypass {
    private val sensitivePackages = setOf(
        "com.kasikorn.retail.mbanking.wap", // K Plus
        "com.scb.phone", // SCB Easy
        "com.bbl.mobilebanking", // Bualuang mBanking
        "com.krungthai.beacon", // Krungthai Next
        "com.gsb.mobile.banking", // GSB
        "com.cimbclicksTH", // CIMB
        "com.uob.mightyth", // UOB
        "com.kbank.service" // fallback kasikorn older
    )

    @Volatile private var currentSensitive = false

    fun updateForeground(packageName: String?) {
        val was = currentSensitive
        currentSensitive = packageName != null && sensitivePackages.contains(packageName)
        if (!was && currentSensitive) {
            OverlayGatekeeper.dismissAll()
        }
    }

    fun isBlocked(): Boolean = currentSensitive
}

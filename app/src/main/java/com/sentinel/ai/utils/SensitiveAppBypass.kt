package com.sentinel.ai.utils

/**
 * Tracks sensitive apps (e.g., banking) to disable overlays while they are foreground.
 */
object SensitiveAppBypass {
    val sensitivePackages = setOf(
        "com.kasikorn.retail.mbanking.wap", // K Plus
        "com.scb.phone", // SCB Easy
        "com.bbl.mobilebanking", // Bualuang mBanking
        "ktbcs.netbank", // Krungthai NEXT
        "com.krungthai.beacon", // Krungthai Next legacy/fallback
        "com.mobilife.gsb.mymo", // MyMo by GSB
        "com.gsb.mobile.banking", // GSB legacy/fallback
        "com.krungsri.kma", // Krungsri
        "com.TMBTOUCH.PRODUCTION", // ttb touch
        "com.cimbclicksTH", // CIMB
        "com.uob.mightyth", // UOB
        "com.kbank.service" // fallback kasikorn older
    )

    // Packages we intentionally allow overlays for (requested bypasses).
    // เป๋าตัง (Krungthai Paotang) package observed on most devices:
    private val bypassPackages = setOf(
        "com.tltid.pao"
    )

    @Volatile private var currentSensitive = false

    fun updateForeground(packageName: String?) {
        val was = currentSensitive
        if (packageName != null && bypassPackages.contains(packageName)) {
            currentSensitive = false
            return
        }
        currentSensitive = packageName != null && sensitivePackages.contains(packageName)
        if (!was && currentSensitive) {
            OverlayGatekeeper.dismissAll()
        }
    }

    fun isSensitivePackage(packageName: String?): Boolean =
        packageName != null && sensitivePackages.contains(packageName) && !bypassPackages.contains(packageName)

    fun isBlocked(): Boolean = currentSensitive
}

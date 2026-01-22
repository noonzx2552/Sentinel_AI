package com.sentinel.ai.utils

import android.content.Intent

/**
 * In-memory cache for MediaProjection grant so we don't re-prompt every call while the app process lives.
 * Note: Android does not allow persisting this across process death, so the user will be asked again
 * after the app is killed or the grant is revoked by the system.
 */
object MediaProjectionStore {
    @Volatile private var cachedResultCode: Int? = null
    @Volatile private var cachedData: Intent? = null

    fun save(resultCode: Int, data: Intent) {
        cachedResultCode = resultCode
        // clone to avoid mutation by callers
        cachedData = Intent(data)
    }

    fun get(): Pair<Int, Intent>? {
        val code = cachedResultCode ?: return null
        val data = cachedData ?: return null
        return code to Intent(data)
    }

    fun clear() {
        cachedResultCode = null
        cachedData = null
    }
}

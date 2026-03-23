package com.sentinel.ai.utils

import android.media.projection.MediaProjection

/**
 * Application-wide holder for a live MediaProjection object.
 *
 * On Android 14+ the createScreenCaptureIntent grant is single-use, so we call
 * getMediaProjection() once (during setup or the very first call) and keep the object here
 * across call sessions for as long as the app process lives — no popup per call.
 *
 * On Android 10-13 the (resultCode, data) stored in MediaProjectionStore can be reused,
 * but we still cache the object here to avoid redundant getMediaProjection() calls.
 */
object MediaProjectionHolder {

    @Volatile private var projection: MediaProjection? = null

    /** Store a live MediaProjection obtained from getMediaProjection(). */
    fun store(mp: MediaProjection) {
        projection = mp
    }

    /** Returns the stored projection, or null if none has been granted yet. */
    fun get(): MediaProjection? = projection

    /** True when a live projection is available and no new user prompt is needed. */
    fun isReady(): Boolean = projection != null

    /**
     * Stop and clear the stored projection.
     * Call this only when the user explicitly revokes capture access or the app exits.
     */
    fun release() {
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }
}

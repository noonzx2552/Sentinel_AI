package com.sentinel.ai.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import com.sentinel.ai.service.CallPlaybackCaptureService
import com.sentinel.ai.utils.MediaProjectionStore

/**
 * One-shot activity to request MediaProjection for call playback capture.
 * On approve: starts CallPlaybackCaptureService and broadcasts CALL_PLAYBACK_CAPTURE_STARTED.
 * On cancel/back: broadcasts CALL_CAPTURE_FALLBACK_MIC so CallModeMonitor can fall back to mic.
 */
class CallMediaProjectionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: throw IllegalStateException("MediaProjectionManager null")
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e("CallMediaProjection", "onCreate failed", e)
            sendBroadcast(Intent(CallPlaybackCaptureService.ACTION_CALL_CAPTURE_FALLBACK_MIC).setPackage(packageName))
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) {
            sendBroadcast(Intent(CallPlaybackCaptureService.ACTION_CALL_CAPTURE_FALLBACK_MIC).setPackage(packageName))
            finish()
            return
        }
        if (resultCode == RESULT_OK && data != null) {
            MediaProjectionStore.save(resultCode, data)
            CallPlaybackCaptureService.start(this, resultCode, data)
            sendBroadcast(Intent(CallPlaybackCaptureService.ACTION_CALL_PLAYBACK_CAPTURE_STARTED).setPackage(packageName))
        } else {
            sendBroadcast(Intent(CallPlaybackCaptureService.ACTION_CALL_CAPTURE_FALLBACK_MIC).setPackage(packageName))
        }
        finish()
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 4001
    }
}

package com.sentinel.ai.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import com.sentinel.ai.utils.LanguageManager

open class BaseLocalizedActivity : AppCompatActivity() {
    private var appliedLanguage: String? = null
    private val languageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LanguageManager.ACTION_LANGUAGE_CHANGED) return
            if (isFinishing || isDestroyed) return
            recreate()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val wrapped = if (shouldApplyAppLanguage()) {
            LanguageManager.wrap(newBase)
        } else {
            newBase
        }
        super.attachBaseContext(wrapped)
    }

    override fun onStart() {
        super.onStart()
        if (shouldApplyAppLanguage()) {
            val filter = IntentFilter(LanguageManager.ACTION_LANGUAGE_CHANGED)
            ContextCompat.registerReceiver(this, languageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (shouldApplyAppLanguage()) {
            appliedLanguage = LanguageManager.getLanguage(this)
        }
    }

    override fun onPostCreate(savedInstanceState: android.os.Bundle?) {
        super.onPostCreate(savedInstanceState)
        animateRootContentIn()
    }

    override fun onResume() {
        super.onResume()
        if (!shouldApplyAppLanguage()) return
        val current = LanguageManager.getLanguage(this)
        val applied = appliedLanguage
        if (applied != null && applied != current && !isFinishing && !isDestroyed) {
            appliedLanguage = current
            recreate()
        }
    }

    override fun onStop() {
        if (shouldApplyAppLanguage()) {
            try {
                unregisterReceiver(languageReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver already unregistered.
            }
        }
        super.onStop()
    }

    protected open fun shouldAnimateRootOnEnter(): Boolean = true

    private fun animateRootContentIn() {
        if (!shouldAnimateRootOnEnter()) return
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        if (root.alpha < 1f) return
        root.alpha = 0f
        root.translationY = 14f
        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    protected open fun shouldApplyAppLanguage(): Boolean = true
}

package com.sentinel.ai.utils

import android.content.Context

object OnboardingPrefs {
    private const val PREFS = "onboarding_prefs"
    private const val KEY_COMPLETE = "onboarding_complete"

    fun isComplete(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETE, false)

    fun setComplete(context: Context, complete: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETE, complete)
            .apply()
    }
}

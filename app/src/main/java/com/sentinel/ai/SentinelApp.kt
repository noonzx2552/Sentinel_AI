package com.sentinel.ai

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.sentinel.ai.model.db.GuardianDatabase
import com.sentinel.ai.utils.LanguageManager

class SentinelApp : Application() {

    lateinit var database: GuardianDatabase
        private set

    override fun attachBaseContext(base: Context) {
        com.sentinel.ai.utils.ProfilePrefs.ensureDefaultTheme(base)
        LanguageManager.ensureDefaultLanguage(base)
        super.attachBaseContext(LanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        LanguageManager.ensureDefaultLanguage(this)
        com.sentinel.ai.utils.ProfilePrefs.ensureDefaultTheme(this)
        instance = this
        com.sentinel.ai.utils.ProfilePrefs.applyTheme(com.sentinel.ai.utils.ProfilePrefs.isDarkMode(this))
        if (com.sentinel.ai.utils.ProtectionPrefs.isEnabled(this) &&
            com.sentinel.ai.utils.PermissionUtils.allEssentialGranted(this)) {
            com.sentinel.ai.service.SentinelGuardianService.start(this)
        }
        database = Room.databaseBuilder(
            applicationContext,
            GuardianDatabase::class.java,
            "guardian_db"
        ).build()
    }

    companion object {
        lateinit var instance: SentinelApp
            private set
    }
}

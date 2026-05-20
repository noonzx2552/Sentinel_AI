package com.sentinel.ai

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        ).addMigrations(MIGRATION_1_2).build()
    }

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN reasonsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE events ADD COLUMN sourceTagsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE events ADD COLUMN protectionMode TEXT")
                db.execSQL("ALTER TABLE events ADD COLUMN phoneNumber TEXT")
                db.execSQL("ALTER TABLE events ADD COLUMN displayName TEXT")
                db.execSQL("ALTER TABLE events ADD COLUMN transcriptSnippet TEXT")
                db.execSQL("ALTER TABLE events ADD COLUMN audioMode TEXT")
                db.execSQL("ALTER TABLE events ADD COLUMN confidence REAL")
                db.execSQL("ALTER TABLE events ADD COLUMN latencyMs INTEGER")
            }
        }

        lateinit var instance: SentinelApp
            private set
    }
}

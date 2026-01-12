package com.sentinel.ai

import android.app.Application
import androidx.room.Room
import com.sentinel.ai.model.db.GuardianDatabase

class SentinelApp : Application() {

    lateinit var database: GuardianDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
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

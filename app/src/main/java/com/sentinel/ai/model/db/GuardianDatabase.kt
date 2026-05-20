package com.sentinel.ai.model.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import com.sentinel.ai.model.GuardianEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface GuardianEventDao {
    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<GuardianEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: GuardianEvent)

    @Query("DELETE FROM events")
    suspend fun clearAll()
}

@Database(entities = [GuardianEvent::class], version = 2, exportSchema = false)
abstract class GuardianDatabase : RoomDatabase() {
    abstract fun eventDao(): GuardianEventDao
}

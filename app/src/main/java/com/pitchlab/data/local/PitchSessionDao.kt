package com.pitchlab.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PitchSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PitchSessionEntity): Long

    @Query("SELECT * FROM pitch_sessions ORDER BY started_at DESC LIMIT 200")
    fun observeRecent(): Flow<List<PitchSessionEntity>>

    @Query("SELECT * FROM pitch_sessions WHERE id = :id")
    suspend fun findById(id: Long): PitchSessionEntity?

    @Query("SELECT COUNT(*) FROM pitch_sessions")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM pitch_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pitch_sessions")
    suspend fun clearAll()
}

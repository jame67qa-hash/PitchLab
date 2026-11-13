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

    @Query("UPDATE pitch_sessions SET clip_path = :clipPath WHERE video_path = :videoPath")
    suspend fun updateClipPath(videoPath: String, clipPath: String)

    /** 刪投手前先把他名下的球改成未指定，避免紀錄變成孤兒而被隱藏 */
    @Query("UPDATE pitch_sessions SET pitcher_id = NULL WHERE pitcher_id = :pitcherId")
    suspend fun detachPitcher(pitcherId: Long)

    @Query("DELETE FROM pitch_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pitch_sessions")
    suspend fun clearAll()
}

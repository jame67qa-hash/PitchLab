package com.pitchlab.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CalibrationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CalibrationEntity): Long

    @Query("SELECT * FROM calibrations ORDER BY created_at DESC LIMIT 1")
    fun observeLatest(): Flow<CalibrationEntity?>

    @Query("SELECT * FROM calibrations ORDER BY created_at DESC LIMIT 1")
    suspend fun findLatest(): CalibrationEntity?

    @Query("DELETE FROM calibrations")
    suspend fun clearAll()
}

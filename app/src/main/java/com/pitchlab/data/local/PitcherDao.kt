package com.pitchlab.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PitcherDao {

    @Query("SELECT * FROM pitchers ORDER BY created_at ASC")
    fun observeAll(): Flow<List<PitcherEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PitcherEntity): Long

    @Query("UPDATE pitchers SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM pitchers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

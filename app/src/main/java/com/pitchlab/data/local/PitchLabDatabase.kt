package com.pitchlab.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PitchSessionEntity::class, CalibrationEntity::class],
    version = 7,   // v7：新增球種標記（Phase 12）
    exportSchema = true
)
abstract class PitchLabDatabase : RoomDatabase() {
    abstract fun pitchSessionDao(): PitchSessionDao
    abstract fun calibrationDao(): CalibrationDao
}

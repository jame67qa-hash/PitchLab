package com.pitchlab.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PitchSessionEntity::class, CalibrationEntity::class],
    version = 5,   // v5：新增相機俯仰角與放球高度/距離（Phase 10）
    exportSchema = true
)
abstract class PitchLabDatabase : RoomDatabase() {
    abstract fun pitchSessionDao(): PitchSessionDao
    abstract fun calibrationDao(): CalibrationDao
}

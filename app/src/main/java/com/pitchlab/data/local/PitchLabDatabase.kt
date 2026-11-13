package com.pitchlab.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PitchSessionEntity::class, CalibrationEntity::class, PitcherEntity::class],
    version = 8,   // v8：新增投手檔案（Phase 16）
    exportSchema = true
)
abstract class PitchLabDatabase : RoomDatabase() {
    abstract fun pitchSessionDao(): PitchSessionDao
    abstract fun calibrationDao(): CalibrationDao
    abstract fun pitcherDao(): PitcherDao
}

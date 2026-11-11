package com.pitchlab.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PitchSessionEntity::class, CalibrationEntity::class],
    version = 3,   // v3：新增 clip_path / release_offset_ms（自動剪輯）
    exportSchema = true
)
abstract class PitchLabDatabase : RoomDatabase() {
    abstract fun pitchSessionDao(): PitchSessionDao
    abstract fun calibrationDao(): CalibrationDao
}

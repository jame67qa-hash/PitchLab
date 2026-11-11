package com.pitchlab.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PitchSessionEntity::class, CalibrationEntity::class],
    version = 4,   // v4：新增姿勢評分欄位（Phase 4）
    exportSchema = true
)
abstract class PitchLabDatabase : RoomDatabase() {
    abstract fun pitchSessionDao(): PitchSessionDao
    abstract fun calibrationDao(): CalibrationDao
}

package com.pitchlab.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pitchlab.core.cv.YuvToRgbConverter
import com.pitchlab.data.local.CalibrationDao
import com.pitchlab.data.local.PitchLabDatabase
import com.pitchlab.data.local.PitchSessionDao
import com.pitchlab.data.repository.RoomCalibrationRepository
import com.pitchlab.data.repository.RoomPitchSessionRepository
import com.pitchlab.domain.cv.CalibrationRegistry
import com.pitchlab.domain.cv.HomographySolver
import com.pitchlab.domain.cv.PitchAnalyzer
import com.pitchlab.domain.cv.PitchMechanicsEvaluator
import com.pitchlab.domain.cv.ReleaseDetector
import com.pitchlab.domain.media.PitchClipExtractor
import com.pitchlab.domain.pose.PoseLandmarkerDetector
import com.pitchlab.domain.repository.CalibrationRepository
import com.pitchlab.domain.repository.PitchSessionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * v1 -> v2：
     * - 新增標定表 calibrations；
     * - pitch_sessions 增加 is_strike / calibrated 兩欄。
     * 這裡寫顯式 migration 而不是 fallbackToDestructiveMigration，
     * 是為了不讓使用者的投球記錄在升級時被清空。
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `calibrations` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`p0x` REAL NOT NULL, `p0y` REAL NOT NULL, " +
                    "`p1x` REAL NOT NULL, `p1y` REAL NOT NULL, " +
                    "`p2x` REAL NOT NULL, `p2y` REAL NOT NULL, " +
                    "`p3x` REAL NOT NULL, `p3y` REAL NOT NULL, " +
                    "`camera_height_in` REAL NOT NULL, " +
                    "`zone_top_in` REAL NOT NULL, " +
                    "`zone_bottom_in` REAL NOT NULL, " +
                    "`speed_scale` REAL NOT NULL, " +
                    "`radar_mph` REAL, `estimated_mph` REAL, " +
                    "`created_at` INTEGER NOT NULL)"
            )
            db.execSQL("ALTER TABLE `pitch_sessions` ADD COLUMN `is_strike` INTEGER")
            db.execSQL(
                "ALTER TABLE `pitch_sessions` ADD COLUMN `calibrated` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * v2 -> v3：自動剪輯欄位。
     * 只加欄位、不改舊欄位，既有紀錄全部保留。
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `pitch_sessions` ADD COLUMN `clip_path` TEXT")
            db.execSQL("ALTER TABLE `pitch_sessions` ADD COLUMN `release_offset_ms` INTEGER")
        }
    }

    /** v3 -> v4：出手姿勢評分欄位（全部 nullable，舊紀錄不受影響） */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `pitch_sessions` ADD COLUMN `pose_score` INTEGER")
            db.execSQL("ALTER TABLE `pitch_sessions` ADD COLUMN `separation_deg` REAL")
            db.execSQL("ALTER TABLE `pitch_sessions` ADD COLUMN `elbow_deg` REAL")
            db.execSQL("ALTER TABLE `pitch_sessions` ADD COLUMN `trunk_tilt_deg` REAL")
            db.execSQL("ALTER TABLE `pitch_sessions` ADD COLUMN `release_height_ratio` REAL")
        }
    }

    /**
     * v4 -> v5：Phase 10 的相機俯仰角與放球高度／距離。
     * 新欄位都有預設值或可為 null，舊資料不需要回填。
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `calibrations` ADD COLUMN `camera_pitch_deg` REAL NOT NULL DEFAULT 0"
            )
            db.execSQL("ALTER TABLE `pitch_sessions` ADD COLUMN `release_height_ft` REAL")
            db.execSQL("ALTER TABLE `pitch_sessions` ADD COLUMN `release_distance_ft` REAL")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PitchLabDatabase =
        Room.databaseBuilder(context, PitchLabDatabase::class.java, "pitchlab.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

    @Provides
    fun providePitchSessionDao(database: PitchLabDatabase): PitchSessionDao =
        database.pitchSessionDao()

    @Provides
    fun provideCalibrationDao(database: PitchLabDatabase): CalibrationDao =
        database.calibrationDao()

    @Provides
    @Singleton
    fun providePitchSessionRepository(
        impl: RoomPitchSessionRepository
    ): PitchSessionRepository = impl

    @Provides
    @Singleton
    fun provideCalibrationRepository(
        impl: RoomCalibrationRepository
    ): CalibrationRepository = impl

    @Provides
    @Singleton
    fun provideYuvToRgbConverter(): YuvToRgbConverter = YuvToRgbConverter()

    @Provides
    @Singleton
    fun provideHomographySolver(): HomographySolver = HomographySolver()

    @Provides
    @Singleton
    fun provideReleaseDetector(): ReleaseDetector = ReleaseDetector()

    @Provides
    @Singleton
    fun provideMechanicsEvaluator(): PitchMechanicsEvaluator = PitchMechanicsEvaluator()

    @Provides
    @Singleton
    fun providePitchClipExtractor(): PitchClipExtractor = PitchClipExtractor()

    @Provides
    @Singleton
    fun providePitchAnalyzer(
        tracker: com.pitchlab.domain.cv.BaseballTracker,
        converter: YuvToRgbConverter
    ): PitchAnalyzer = PitchAnalyzer(tracker, converter)
}

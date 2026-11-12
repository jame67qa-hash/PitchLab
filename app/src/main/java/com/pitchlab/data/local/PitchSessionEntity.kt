package com.pitchlab.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pitch_sessions")
data class PitchSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "started_at")
    val startedAtMillis: Long,

    @ColumnInfo(name = "duration_ms")
    val durationMillis: Long,

    @ColumnInfo(name = "fastest_mph")
    val fastestMph: Double?,

    @ColumnInfo(name = "average_mph")
    val averageMph: Double?,

    @ColumnInfo(name = "horizontal_break_in")
    val horizontalBreakInches: Double?,

    @ColumnInfo(name = "vertical_break_in")
    val verticalBreakInches: Double?,

    @ColumnInfo(name = "plate_x_in")
    val plateXInches: Double?,

    @ColumnInfo(name = "plate_y_in")
    val plateYInches: Double?,

    @ColumnInfo(name = "is_strike")
    val isStrike: Boolean?,

    // defaultValue 必須寫，否則 Room 開啟時會把「migration 加上的 DEFAULT 0」當成 schema 不符
    @ColumnInfo(name = "calibrated", defaultValue = "0")
    val calibrated: Boolean,

    @ColumnInfo(name = "video_path")
    val videoPath: String?,

    @ColumnInfo(name = "detected_fps")
    val detectedFps: Int,

    @ColumnInfo(name = "tracked_frames")
    val trackedFrames: Int,

    @ColumnInfo(name = "created_at")
    val createdAtMillis: Long,

    /** 自動剪輯產生的片段路徑（null = 尚未剪輯或剪輯失敗） */
    @ColumnInfo(name = "clip_path")
    val clipPath: String? = null,

    /** 出手時間（相對錄影開始，毫秒）；用於剪輯定位 */
    @ColumnInfo(name = "release_offset_ms")
    val releaseOffsetMs: Long? = null,

    /** Phase 4 出手姿勢評分（0–100）與四項量測 */
    @ColumnInfo(name = "pose_score")
    val poseScore: Int? = null,
    @ColumnInfo(name = "separation_deg")
    val separationDeg: Double? = null,
    @ColumnInfo(name = "elbow_deg")
    val elbowDeg: Double? = null,
    @ColumnInfo(name = "trunk_tilt_deg")
    val trunkTiltDeg: Double? = null,
    @ColumnInfo(name = "release_height_ratio")
    val releaseHeightRatio: Double? = null,

    /** Phase 10 3D 重建：放球高度／放球距離（英尺，含相機俯仰角修正） */
    @ColumnInfo(name = "release_height_ft")
    val releaseHeightFt: Double? = null,
    @ColumnInfo(name = "release_distance_ft")
    val releaseDistanceFt: Double? = null
)

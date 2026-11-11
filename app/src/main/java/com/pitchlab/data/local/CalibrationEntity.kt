package com.pitchlab.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 標定結果的扁平化存儲。
 * 四個角點攤平成 8 個 DOUBLE 欄位，比存 JSON 更利於查詢與 migration 校驗。
 */
@Entity(tableName = "calibrations")
data class CalibrationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "p0x") val p0x: Double,
    @ColumnInfo(name = "p0y") val p0y: Double,
    @ColumnInfo(name = "p1x") val p1x: Double,
    @ColumnInfo(name = "p1y") val p1y: Double,
    @ColumnInfo(name = "p2x") val p2x: Double,
    @ColumnInfo(name = "p2y") val p2y: Double,
    @ColumnInfo(name = "p3x") val p3x: Double,
    @ColumnInfo(name = "p3y") val p3y: Double,

    @ColumnInfo(name = "camera_height_in") val cameraHeightIn: Double,
    @ColumnInfo(name = "zone_top_in") val strikeZoneTopIn: Double,
    @ColumnInfo(name = "zone_bottom_in") val strikeZoneBottomIn: Double,
    @ColumnInfo(name = "speed_scale") val speedScale: Double,
    @ColumnInfo(name = "radar_mph") val radarReferenceMph: Double?,
    @ColumnInfo(name = "estimated_mph") val estimatedAtCalibrationMph: Double?,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long
)

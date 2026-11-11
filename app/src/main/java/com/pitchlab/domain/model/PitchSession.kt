package com.pitchlab.domain.model

/**
 * 一條投球記錄（領域模型）。
 * 數據庫實體在 data 層獨立定義，避免 Room 註解污染 domain layer。
 */
data class PitchSession(
    val id: Long = 0L,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val fastestMph: Double? = null,
    val averageMph: Double? = null,
    val horizontalBreakInches: Double? = null,
    val verticalBreakInches: Double? = null,
    val plateXInches: Double? = null,
    val plateYInches: Double? = null,
    /** 好球帶判定結果；null 表示當下沒有落點，無法判定 */
    val isStrike: Boolean? = null,
    /** 這一球的速度是否經過雷達校準（UI 顯示 CAL / EST） */
    val calibrated: Boolean = false,
    val videoPath: String? = null,
    val detectedFps: Int = 0,
    val trackedFrames: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** 自動剪輯片段路徑（出手前後各 2 秒） */
    val clipPath: String? = null,
    /** 出手時間相對錄影開始的偏移（毫秒） */
    val releaseOffsetMs: Long? = null,
    /** 出手影格姿勢評分 0–100；null = 未偵測到姿勢 */
    val poseScore: Int? = null,
    val separationDeg: Double? = null,
    val elbowDeg: Double? = null,
    val trunkTiltDeg: Double? = null,
    val releaseHeightRatio: Double? = null
)

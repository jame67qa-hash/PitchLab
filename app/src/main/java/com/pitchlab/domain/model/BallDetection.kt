package com.pitchlab.domain.model

/**
 * 單幀內被追蹤器確認的棒球觀測值。
 *
 * 座標系約定：全部爲「已轉正（upright）」分析幀的像素座標，
 * 原點在圖像左上角，x 向右、y 向下，與 Compose Canvas 一致。
 */
data class BallDetection(
    val xPx: Double,
    val yPx: Double,
    val radiusPx: Double,
    val confidence: Double,
    val frameWidth: Int,
    val frameHeight: Int
) {
    /** 歸一化中心點（0..1），用於跨解析度映射到 UI 疊加層 */
    val normalizedX: Float get() = (xPx / frameWidth).toFloat()
    val normalizedY: Float get() = (yPx / frameHeight).toFloat()

    /** 歸一化半徑（相對圖像寬度），用於在 UI 上畫等高球標記 */
    val normalizedRadius: Float get() = (radiusPx / frameWidth).toFloat()
}

/** 單幀檢測來源，便於調參與日誌區分算法路徑 */
enum class DetectionSource {
    /** 背景相減 MOG2 + 輪廓篩選（預設，灰度、低開銷） */
    MOG2_MOTION,

    /** HSV 白色過濾 + 形態學（需要 RGB 幀，精度高但更耗 CPU） */
    HSV_WHITE,

    /** 本幀無可靠球體 */
    NONE
}

/**
 * 一幀分析的完整結果，由 [com.pitchlab.domain.cv.PitchAnalyzer] 發出。
 *
 * 這是 UI 層唯一能看到的數據契約：UI 不需要知道 OpenCV Mat 的存在。
 */
data class FrameAnalysisResult(
    val frameIndex: Long,
    val timestampNanos: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val ball: BallDetection?,
    val source: DetectionSource,
    val processingMillis: Double,
    val measuredFps: Double,
    val candidateContours: Int
) {
    val hasBall: Boolean get() = ball != null
}

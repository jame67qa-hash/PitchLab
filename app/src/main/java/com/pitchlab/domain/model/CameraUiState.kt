package com.pitchlab.domain.model

/** 歸一化點（0..1，相對分析幀） */
data class NormalizedPoint(val x: Float, val y: Float)

/** 歸一化矩形（0..1，相對分析幀） */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** 本壘板平面上的落點（英寸，相對相機光軸；y 向下為正） */
data class PlateLocation(val xInches: Double, val yInches: Double)

/**
 * 一次投球的實時指標快照。
 * 未做雷達校準時速度為「單目深度估計」結果，[speedCalibrated] = false，UI 應標 EST。
 */
data class LivePitchMetrics(
    val trackedFrames: Int = 0,
    val timeToPlateMillis: Long? = null,
    val instantaneousMph: Double? = null,
    val averageMph: Double? = null,
    val releaseDetected: Boolean = false,
    val horizontalBreakInches: Double? = null,
    val verticalBreakInches: Double? = null,
    val plateLocation: PlateLocation? = null,
    val isStrike: Boolean? = null,
    val strikeHeightInches: Double? = null,
    val strikeHorizontalInches: Double? = null,
    val speedCalibrated: Boolean = false,
    val confidence: Double = 0.0
) {
    companion object {
        val EMPTY = LivePitchMetrics()
    }
}

/**
 * 標定模式（在相機頁上以覆蓋層呈現，不再另開相機實例）的 UI 狀態。
 *
 * 流程：抓幀 -> 依序點本壘板四角 -> 設定相機高度 -> 計算 -> 輸入雷達值校準速度 -> 儲存
 */
data class CalibrationUiState(
    val visible: Boolean = false,
    val hasFrame: Boolean = false,
    val corners: List<NormalizedPoint> = emptyList(),
    val cameraHeightIn: Float = CalibrationProfile.DEFAULT_CAMERA_HEIGHT_IN.toFloat(),
    val zoneTopIn: Float = CalibrationProfile.DEFAULT_TOP_IN.toFloat(),
    val zoneBottomIn: Float = CalibrationProfile.DEFAULT_BOTTOM_IN.toFloat(),
    val plateWidthPx: Double = 0.0,
    val pixelsPerInch: Double = 0.0,
    /** 四角重投影誤差（英吋，越小代表點得越準） */
    val reprojectionErrorIn: Double = 0.0,
    val radarMphInput: String = "",
    val estimatedMphInput: String = "",
    val speedScalePreview: Double? = null,
    val message: String? = null,
    val speedCalibrated: Boolean = false
) {
    val nextCornerHint: String
        get() = when (corners.size) {
            0 -> "點擊本壘板「左後角」"
            1 -> "點擊「右後角」"
            2 -> "點擊「右前角」"
            3 -> "點擊「左前角」"
            else -> "四角已完整，可計算並儲存"
        }
}

/** 相機頁面的單一狀態源（MVVM：UI 只讀 StateFlow） */
data class CameraUiState(
    val cameraReady: Boolean = false,
    val videoSupported: Boolean = true,
    val isRecording: Boolean = false,
    val colorModeEnabled: Boolean = false,
    val isAnalyzing: Boolean = true,
    val requestedFps: Int = 0,
    val requestRangeLabel: String = "",
    val measuredFps: Double = 0.0,
    val lastProcessingMs: Double = 0.0,
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val ballDetected: Boolean = false,
    val detectionSource: DetectionSource = DetectionSource.NONE,
    val ballMarker: NormalizedPoint? = null,
    val ballRadiusNorm: Float = 0f,
    val liveMetrics: LivePitchMetrics = LivePitchMetrics.EMPTY,
    val pitchCount: Int = 0,
    val storedSessionCount: Int = 0,
    val strikeCount: Int = 0,
    val ballCount: Int = 0,
    val strikeZone: NormalizedRect = NormalizedRect(0.30f, 0.38f, 0.70f, 0.80f),
    val statusMessage: String = "等待相機初始化",
    val errorMessage: String? = null,
    val lastRecordingPath: String? = null,
    /** 速度是否已用雷達校準（UI 顯示 CAL / EST） */
    val speedCalibrated: Boolean = false,
    /** 幾何標定是否可用（好球帶框由本壘板四角推出） */
    val geometryCalibrated: Boolean = false,
    val calibrationSummary: String = "尚未標定",
    /** 姿態模型是否可用（可用 = 出手偵測 + 自動剪輯生效） */
    val poseAvailable: Boolean = false,
    /** 最近一次出手相對錄影起點的偏移（毫秒） */
    val releaseOffsetMs: Long? = null,
    /** 最近產生的出手片段路徑 */
    val lastClipPath: String? = null,
    /** 最近一次出手的姿勢分數與等級（Phase 4） */
    val mechanicsScore: Int? = null,
    val mechanicsGrade: String? = null,
    /** 出手回饋（語音＋震動）是否開啟；預設關閉 */
    val feedbackEnabled: Boolean = false,
    val calibration: CalibrationUiState = CalibrationUiState()
) {
    /** 分析幀的寬高比（已轉正），UI 用它計算 letterbox 內容區 */
    val analysisAspectRatio: Float
        get() = if (analysisHeight > 0) {
            analysisWidth.toFloat() / analysisHeight.toFloat()
        } else {
            9f / 16f
        }
}

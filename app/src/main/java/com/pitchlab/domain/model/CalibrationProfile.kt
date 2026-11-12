package com.pitchlab.domain.model

/**
 * 一次「場地標定」的結果（領域模型）。
 *
 * 內容刻意只存**原始輸入**（四個角點的歸一化座標 + 相機高度 + 好球帶英吋值），
 * 不存單應矩陣本體：矩陣可以在任何時候由角點重算，存死矩陣反而會在
 * 分析解析度變更（720p ↔ 1080p）時失效。
 *
 * 角點順序固定為：本壘板左後 → 右後 → 右前 → 左前（俯視看是逆時針），
 * 對應場地座標 (-8.5,-8.5) (+8.5,-8.5) (+8.5,0) (-8.5,0)（英吋，y 往投手方向為負）。
 */
data class CalibrationProfile(
    val id: Long = 0L,

    /** 四個角點，歸一化 0..1（相對分析幀） */
    val corners: List<NormalizedPoint> = emptyList(),

    /** 相機鏡頭離地高度（英吋），好球帶高度換算的基準 */
    val cameraHeightIn: Double = 44.0,

    /** 鏡頭俯仰角（度）：正 = 上仰，負 = 下俯。Phase 10 起用於 3D 重建與好球帶高度修正 */
    val cameraPitchDeg: Double = 0.0,

    /** 好球帶上下緣（英吋，相對地面） */
    val strikeZoneTopIn: Double = 42.0,
    val strikeZoneBottomIn: Double = 19.0,

    /** 速度刻度：雷達實測 / App 估算。1.0 表示未校準 */
    val speedScale: Double = 1.0,

    /** 校準當下的雷達讀值與 App 估算值，僅為顯示與回溯用 */
    val radarReferenceMph: Double? = null,
    val estimatedAtCalibrationMph: Double? = null,

    val createdAtMillis: Long = System.currentTimeMillis()
) {
    val isValid: Boolean get() = corners.size == 4

    val isSpeedCalibrated: Boolean get() = kotlin.math.abs(speedScale - 1.0) > 0.005

    /** 供 UI 一行摘要 */
    fun summary(): String {
        if (!isValid) return "尚未標定"
        val speed = if (isSpeedCalibrated) String.format("%.2f×", speedScale) else "未校準"
        return "相機 ${String.format("%.0f", cameraHeightIn)} in · 俯仰 " +
            String.format("%.1f", cameraPitchDeg) + "° · 速度刻度 $speed"
    }

    companion object {
        /** 空的標定（尚未做任何校準） */
        val NONE = CalibrationProfile()

        /** 好球帶預設（成人平均） */
        const val DEFAULT_TOP_IN = 42.0
        const val DEFAULT_BOTTOM_IN = 19.0

        /** 三腳架高度預設值 */
        const val DEFAULT_CAMERA_HEIGHT_IN = 44.0
    }
}

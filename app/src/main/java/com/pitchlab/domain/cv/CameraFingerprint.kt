package com.pitchlab.domain.cv

import com.pitchlab.domain.model.PitchSession
import kotlin.math.abs

/**
 * 機位指紋：相機高度 + 俯仰角 + 速度刻度。
 *
 * 為什麼需要它：個人基準是用「歷史中位數」建立的，但同一個投手換了腳架高度、
 * 換了上仰角度、或重新校準了速度刻度，投影就變了——同一個 28° 肩髖分離
 * 在新機位下可能是完全不同的影像。把不同機位的球混進同一個基準，
 * 等於拿兩把不同的尺去平均，數字看似有效其實沒有意義。
 *
 * 因此基準計算前先用指紋篩選，只留下「與目前機位一致」的球，
 * 其餘列入 [MechanicsBaseline.ignoredSampleCount]，UI 明確說明略過幾球。
 */
data class CameraFingerprint(
    val heightIn: Double,
    val pitchDeg: Double,
    val speedScale: Double = 1.0
) {

    companion object {
        /** 腳架高度差 2 in 以內視為同一機位（一般腳架調整的最小有感幅度） */
        const val HEIGHT_TOLERANCE_IN = 2.0

        /** 俯仰角差 1.5° 以內視為同一機位 */
        const val PITCH_TOLERANCE_DEG = 1.5

        /** 速度刻度差 10% 以內視為同一校準（更大的差異代表重新校準過） */
        const val SPEED_SCALE_TOLERANCE = 0.10

        /** 吸收二進位浮點誤差，否則「差 0.10」可能被判成 0.10000000000000009 */
        private const val EPS = 1e-9

        /** 從一球紀錄取出機位指紋；舊資料（Phase 10 以前）三個欄位都是 null，回傳 null */
        fun of(session: PitchSession): CameraFingerprint? {
            val height = session.cameraHeightIn ?: return null
            val pitch = session.cameraPitchDeg ?: return null
            return CameraFingerprint(
                heightIn = height,
                pitchDeg = pitch,
                speedScale = session.speedScale ?: 1.0
            )
        }
    }

    fun matches(other: CameraFingerprint): Boolean =
        abs(heightIn - other.heightIn) <= HEIGHT_TOLERANCE_IN + EPS &&
            abs(pitchDeg - other.pitchDeg) <= PITCH_TOLERANCE_DEG + EPS &&
            abs(speedScale - other.speedScale) <= SPEED_SCALE_TOLERANCE + EPS

    /** 給 UI 的一行摘要，例如「機位 44 in · +3.0°」 */
    fun label(): String = String.format(
        java.util.Locale.US,
        "機位 %.0f in · %+.1f°",
        heightIn,
        pitchDeg
    )
}

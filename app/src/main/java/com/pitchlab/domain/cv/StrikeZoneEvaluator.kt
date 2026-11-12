package com.pitchlab.domain.cv

import com.pitchlab.domain.model.CalibrationProfile
import com.pitchlab.domain.model.PlateLocation
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 好球帶判定。
 *
 * 幾何近似（必須在文件裡說清楚，否則數字會被誤讀）：
 * 落點由 [PitchMetricsCalculator] 外推到 z = 0（本壘板平面）得到相機座標 (x, y)，
 * 其中 y **向下為正**。假設相機光軸水平、鏡頭高度 = cameraHeightIn，
 * 則球通過本壘板平面時離地高度 ≈ cameraHeightIn - y。
 * 這個近似忽略了俯仰角（相機上仰/下俯），所以標定時要求「鏡頭盡量水平」。
 *
 * 好球帶規則（MLB 官方口徑）：
 * - 橫向：以本壘板 17 in 為準，中心左右各 8.5 in；
 * - 縱向：下緣為膝蓋高度、上緣為胸口高度，本 App 用可調的 19 / 42 in 近似，
 *   真正的個人化應該在 Phase 3 由打者身高換算。
 */
@Singleton
class StrikeZoneEvaluator @Inject constructor(
    private val reconstructor: TrajectoryReconstructor
) {

    data class Result(
        val isStrike: Boolean,
        val heightInches: Double?,
        val horizontalInches: Double?,
        val zoneHalfWidthIn: Double,
        val zoneBottomIn: Double,
        val zoneTopIn: Double,
        /** true = 有標定/可信輸入；false = 純估算，UI 應標 EST */
        val calibrated: Boolean
    )

    companion object {
        const val PLATE_HALF_WIDTH_IN = 8.5

        /** 球半徑：壓在線上的球（含邊緣）在轉播判定裡算好球，這裡保留整顆球的寬容度 */
        const val BALL_RADIUS_IN = 1.47
    }

    fun evaluate(
        plate: PlateLocation?,
        profile: CalibrationProfile,
        fallbackCameraHeightIn: Double = CalibrationProfile.DEFAULT_CAMERA_HEIGHT_IN
    ): Result? {
        if (plate == null) return null
        val cameraHeight = if (profile.cameraHeightIn > 0.0) {
            profile.cameraHeightIn
        } else {
            fallbackCameraHeightIn
        }
        // Phase 10：含俯仰角的 cos 修正（原本是 h - y 的線性近似，忽略鏡頭上仰）
        val height = reconstructor.plateHeightInches(
            plateCameraYIn = plate.yInches,
            setup = CameraSetup(heightIn = cameraHeight, pitchDeg = profile.cameraPitchDeg)
        )
        val horizontal = plate.xInches

        val halfWidth = PLATE_HALF_WIDTH_IN + BALL_RADIUS_IN
        val top = profile.strikeZoneTopIn + BALL_RADIUS_IN
        val bottom = profile.strikeZoneBottomIn - BALL_RADIUS_IN

        val isStrike = abs(horizontal) <= halfWidth && height in bottom..top

        return Result(
            isStrike = isStrike,
            heightInches = height,
            horizontalInches = horizontal,
            zoneHalfWidthIn = PLATE_HALF_WIDTH_IN,
            zoneBottomIn = profile.strikeZoneBottomIn,
            zoneTopIn = profile.strikeZoneTopIn,
            calibrated = profile.isValid
        )
    }
}

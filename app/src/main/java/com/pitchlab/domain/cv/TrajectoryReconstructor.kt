package com.pitchlab.domain.cv

import com.pitchlab.domain.model.Point3D
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin

/**
 * 相機的實體安裝參數。
 *
 * @param heightIn 鏡頭離地高度（英寸）
 * @param pitchDeg 鏡頭俯仰角：正值 = 鏡頭上仰（看向投手方向），負值 = 下俯
 */
data class CameraSetup(
    val heightIn: Double = 44.0,
    val pitchDeg: Double = 0.0
)

/**
 * 世界座標點：X 向右、Y 向上（離地高度）、Z 朝投手方向。
 * 原點在地面投影的相機位置。
 */
data class WorldPoint(
    val xInches: Double,
    val yInches: Double,
    val zInches: Double
)

/**
 * 把相機座標系的三維點轉到世界座標系（含俯仰角補正）。
 *
 * == 為什麼需要這個 ==
 * Phase 1–9 的落點高度直接用「相機高度 − 影像縱向偏移」，前提是**光軸水平**。
 * 實務上三腳架常常略為上仰（拍投手全身），若上仰 5°、球在 55 ft 外，
 * 光軸本身就把「同一個像素高度」抬高了 55ft·sin5° ≈ 5.8 ft 的錯覺；
 * 忽略俯仰角會讓放球高度與好球帶高度系統性偏低。
 *
 * 旋轉只繞 X 軸（假設相機沒有滾轉），相機 y 軸向下為正：
 *   X_w = x_cam
 *   Y_w = h − y_cam·cosθ + z_cam·sinθ      （y_cam 向下為正，所以是減）
 *   Z_w = y_cam·sinθ + z_cam·cosθ
 * 驗證：光軸上的點（y_cam = 0）在 z 遠處高度為 h + z·sinθ，符合「上仰看到更高」；
 * 水平時回到 h − y_cam，與 Phase 2 的算法一致。
 */
@Singleton
class TrajectoryReconstructor @Inject constructor() {

    companion object {
        /** 合理的放球高度（英尺）；超出視為誤檢，不回傳 */
        const val MIN_RELEASE_HEIGHT_FT = 2.0
        const val MAX_RELEASE_HEIGHT_FT = 12.0

        /** 合理的放球距離（英尺，距本壘板）；職棒出手約 50–57 ft */
        const val MIN_RELEASE_DISTANCE_FT = 30.0
        const val MAX_RELEASE_DISTANCE_FT = 70.0
    }

    fun reconstruct(cameraPoint: Point3D, setup: CameraSetup): WorldPoint {
        val theta = Math.toRadians(setup.pitchDeg)
        val cosT = cos(theta)
        val sinT = sin(theta)
        return WorldPoint(
            xInches = cameraPoint.xInches,
            yInches = setup.heightIn - cameraPoint.yInches * cosT + cameraPoint.zInches * sinT,
            zInches = cameraPoint.yInches * sinT + cameraPoint.zInches * cosT
        )
    }

    /**
     * 放球高度（英尺）。[cameraPoint] 取出手那一幀的相機座標。
     * 不合理值回傳 null，避免把誤檢寫進資料庫。
     */
    fun releaseHeightFeet(cameraPoint: Point3D?, setup: CameraSetup): Double? {
        if (cameraPoint == null) return null
        val feet = reconstruct(cameraPoint, setup).yInches / 12.0
        return feet.takeIf { it in MIN_RELEASE_HEIGHT_FT..MAX_RELEASE_HEIGHT_FT }
    }

    /**
     * 放球距離（英尺，離本壘板）。本壘板平面在相機座標 z = 0，
     * 投手方向為正，因此出手 z 直接就是「離壘幾英寸」。
     */
    fun releaseDistanceFeet(cameraPoint: Point3D?, setup: CameraSetup): Double? {
        if (cameraPoint == null) return null
        val feet = reconstruct(cameraPoint, setup).zInches / 12.0
        return feet.takeIf { it in MIN_RELEASE_DISTANCE_FT..MAX_RELEASE_DISTANCE_FT }
    }

    /**
     * 好球帶高度修正：球通過本壘板平面時 z≈0，只剩俯仰角的 cos 項。
     * 供 [StrikeZoneEvaluator] 取代原本「相機高度 − y」的線性近似。
     */
    fun plateHeightInches(plateCameraYIn: Double, setup: CameraSetup): Double {
        val theta = Math.toRadians(setup.pitchDeg)
        return setup.heightIn - plateCameraYIn * cos(theta)
    }
}

package com.pitchlab.domain.cv

import com.pitchlab.domain.model.CalibrationProfile
import com.pitchlab.domain.model.NormalizedPoint
import com.pitchlab.domain.model.NormalizedRect
import kotlin.math.abs
import kotlin.math.max
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 由標定角點推導畫面幾何量（純數學，可單元測試）。
 *
 * 座標系：角點一律是「歸一化影像座標」，x 向右、y 向下。
 * 因為影像的 x/y 各自歸一化，同樣 1 英吋在 x 與 y 方向的歸一化長度**不同**：
 *   1 in (x) = wNorm / 17
 *   1 in (y) = wNorm * aspect / 17        （aspect = 影像寬/高）
 * 這是最容易寫錯的地方，好球帶框若少乘 aspect 就會變成一條扁線。
 */
@Singleton
class CalibrationGeometry @Inject constructor() {

    companion object {
        /** 本壘板寬 17 in（見 PerspectiveTransformCalculator.PLATE_WIDTH_IN） */
        const val PLATE_WIDTH_IN = 17.0

        /** 本壘板縱深 8.5 in */
        const val PLATE_DEPTH_IN = 8.5
    }

    /** 本壘板在畫面中的平均寬度（歸一化 x 單位）；四點無效返回 0 */
    fun plateWidthNormalized(corners: List<NormalizedPoint>): Double {
        if (corners.size != 4) return 0.0
        // 0-1 是後緣、2-3 是前緣
        val back = abs(corners[1].x - corners[0].x).toDouble()
        val front = abs(corners[2].x - corners[3].x).toDouble()
        return (back + front) / 2.0
    }

    /** 本壘板中心 x（歸一化） */
    fun plateCenterX(corners: List<NormalizedPoint>): Double {
        if (corners.size != 4) return 0.5
        return corners.map { it.x.toDouble() }.average()
    }

    /** 本壘板前緣 y（歸一化，畫面中較靠下的一條邊） */
    fun plateFrontY(corners: List<NormalizedPoint>): Double {
        if (corners.size != 4) return 0.75
        return max(corners[2].y.toDouble(), corners[3].y.toDouble())
    }

    /** 每個英吋對應多少歸一化 y（好球帶高度換算用） */
    fun normalizedYPerInch(corners: List<NormalizedPoint>, aspectRatio: Float): Double {
        val widthNorm = plateWidthNormalized(corners)
        if (widthNorm <= 0.0 || aspectRatio <= 0f) return 0.0
        return widthNorm * aspectRatio / PLATE_WIDTH_IN
    }

    /**
     * 依真實尺寸推出好球帶框（歸一化）。
     *
     * 橫向：本壘板寬度即 17 in，好球帶寬度同為 17 in（規則上以本壘板為準）。
     * 縱向：由本壘板前緣往上量 strikeZoneBottomIn / TopIn。
     */
    fun strikeZoneRect(profile: CalibrationProfile, aspectRatio: Float): NormalizedRect? {
        if (!profile.isValid || aspectRatio <= 0f) return null
        val corners = profile.corners
        val widthNorm = plateWidthNormalized(corners)
        if (widthNorm <= 0.0) return null

        val normYPerInch = normalizedYPerInch(corners, aspectRatio)
        val centerX = plateCenterX(corners)
        val edge = (PLATE_WIDTH_IN / 2.0) / PLATE_WIDTH_IN * widthNorm
        val frontY = plateFrontY(corners)
        val bottomY = frontY - profile.strikeZoneBottomIn * normYPerInch
        val topY = frontY - profile.strikeZoneTopIn * normYPerInch

        return NormalizedRect(
            left = (centerX - edge).toFloat().coerceIn(0f, 1f),
            top = topY.toFloat().coerceIn(0f, 1f),
            right = (centerX + edge).toFloat().coerceIn(0f, 1f),
            bottom = bottomY.toFloat().coerceIn(0f, 1f)
        )
    }

    /** 標定品質指標：每英吋多少像素（影像寬度已知時） */
    fun pixelsPerInch(corners: List<NormalizedPoint>, imageWidthPx: Int): Double {
        if (imageWidthPx <= 0) return 0.0
        return plateWidthNormalized(corners) * imageWidthPx / PLATE_WIDTH_IN
    }
}

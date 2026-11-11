package com.pitchlab.domain.cv

import com.pitchlab.core.camera.CameraIntrinsics
import com.pitchlab.domain.model.Point3D
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 像素座標 <-> 物理空間座標 的換算中心。
 *
 * == 單目測速的物理基礎 ==
 * 1) 深度（球離相機多遠）由「已知球徑 + 像素半徑」估計：
 *      Z = f_px * R_real / r_px          （R_real = 1.47 in 半徑）
 *    這依賴相機焦距 f_px；f_px 由物理焦距與傳感器尺寸換算（見 CameraIntrinsics）。
 * 2) 有了 Z，像素點即可反投影成相機座標系三維點：
 *      X = (u - cx) * Z / fx
 *      Y = (v - cy) * Z / fy
 * 3) 速度 = 三維點序列對時間做最小二乘擬合得到的 |v|，1 mph = 17.6 in/s。
 *
 * == 單應矩陣（Perspective Transform）==
 * 用於 Phase 2 的四點標定：把本壘板平面的像素座標（打擊區四角）
 * 映射到真實場地座標（英寸），從而得到「落點 X/Y」而非「螢幕 X/Y」。
 * 本類已完整實現 findHomography + perspectiveTransform，Phase 1 UI 尚未接入標定向導。
 */
@Singleton
class PerspectiveTransformCalculator @Inject constructor() {

    companion object {
        /** 投手板到本壘板 60.5 英尺 */
        const val PITCH_DISTANCE_FT = 60.5

        /** 60.5 ft * 12 */
        const val PITCH_DISTANCE_IN = 726.0

        /** 棒球直徑 2.94 in（周長 9.125 in 左右） */
        const val BALL_DIAMETER_IN = 2.94

        /** 棒球半徑，用於深度估計 */
        const val BALL_RADIUS_IN = BALL_DIAMETER_IN / 2.0

        /** 本壘板寬度 17 in */
        const val PLATE_WIDTH_IN = 17.0

        /** 好球帶上下沿（相對地面，近似成人平均值，Phase 2 由球員身高換算） */
        const val STRIKE_ZONE_TOP_IN = 42.0
        const val STRIKE_ZONE_BOTTOM_IN = 19.0

        /** 1 mph = 17.6 inch/second */
        const val INCH_PER_SEC_PER_MPH = 17.6
    }

    // ---------------------------------------------------------------- 投影幾何

    /** 由物理焦距 / 傳感器尺寸 / 圖像寬度換算像素焦距 */
    fun focalLengthPx(sensorWidthMm: Double, physicalFocalMm: Double, imageWidthPx: Int): Double {
        if (sensorWidthMm <= 0.0 || imageWidthPx <= 0) return 0.0
        return physicalFocalMm / sensorWidthMm * imageWidthPx
    }

    /**
     * 由球體像素半徑估計深度（英寸）。半徑直接取 [radiusPx]，返回 0 表示不可用。
     */
    fun depthFromApparentRadius(radiusPx: Double, focalPx: Double, realRadiusIn: Double = BALL_RADIUS_IN): Double {
        if (radiusPx <= 0.0 || focalPx <= 0.0) return 0.0
        return focalPx * realRadiusIn / radiusPx
    }

    /** 像素點 + 深度 -> 相機座標系三維點（英寸） */
    fun projectToCamera3D(
        uPx: Double,
        vPx: Double,
        depthIn: Double,
        intrinsics: CameraIntrinsics
    ): Point3D {
        val x = (uPx - intrinsics.principalPointX) * depthIn / intrinsics.focalLengthPxX
        val y = (vPx - intrinsics.principalPointY) * depthIn / intrinsics.focalLengthPxY
        return Point3D(xInches = x, yInches = y, zInches = depthIn)
    }

    // ---------------------------------------------------------------- 速度換算

    /** 由「距離 + 耗時」算平均球速（mph），用於投手板到本壘板的整段飛行 */
    fun averageSpeedMph(distanceInches: Double, elapsedMillis: Long): Double {
        if (elapsedMillis <= 0) return 0.0
        val seconds = elapsedMillis / 1000.0
        val inchesPerSecond = distanceInches / seconds
        return inchesPerSecond / INCH_PER_SEC_PER_MPH
    }

    /** 英寸/秒 -> mph */
    fun inchesPerSecondToMph(inchesPerSecond: Double): Double =
        inchesPerSecond / INCH_PER_SEC_PER_MPH

    /**
     * 空氣阻力修正：雷達槍在出手點附近測得的是「出手速度」，
     * 用兩幀平均速度會低估 2~4 mph。此處按 3.5% 補償並給出可調參數。
     */
    fun releaseSpeedEstimateMph(averageMph: Double, dragFactor: Double = 1.035): Double =
        if (averageMph <= 0.0) 0.0 else averageMph * dragFactor

    /** 三維速度向量長度（英寸/秒） -> mph */
    fun speedMphFromVelocity(vx: Double, vy: Double, vz: Double): Double =
        hypot(hypot(vx, vy), vz) / INCH_PER_SEC_PER_MPH

    // ---------------------------------------------------------------- 單應矩陣

    /**
     * 四點單應矩陣：圖像座標 -> 場地座標（英寸）。
     *
     * @param imagePoints 四角像素座標（順序需與 fieldPoints 對應）
     * @param fieldPoints 對應的場地座標（英寸），例如本壘板四角 (-8.5,0) (8.5,0) ...
     * @return 3x3 CV_64F 單應矩陣；點數不足或退化返回 null
     */
    fun homography(imagePoints: List<Point>, fieldPoints: List<Point>): Mat? {
        if (imagePoints.size < 4 || fieldPoints.size < 4) return null
        val src = MatOfPoint2f(*imagePoints.take(4).toTypedArray())
        val dst = MatOfPoint2f(*fieldPoints.take(4).toTypedArray())
        return try {
            val mask = Mat()
            val h = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 3.0, mask)
            mask.release()
            if (h.empty()) {
                h.release()
                null
            } else {
                h
            }
        } finally {
            src.release()
            dst.release()
        }
    }

    /** 應用單應矩陣：像素點 -> 場地座標 */
    fun imageToField(homography: Mat, xPx: Double, yPx: Double): Point? {
        if (homography.empty()) return null
        val src = Mat(1, 1, CvType.CV_32FC2)
        val dst = Mat(1, 1, CvType.CV_32FC2)
        return try {
            src.put(0, 0, floatArrayOf(xPx.toFloat(), yPx.toFloat()))
            Core.perspectiveTransform(src, dst, homography)
            val out = DoubleArray(2)
            dst.get(0, 0, out)
            Point(out[0], out[1])
        } finally {
            src.release()
            dst.release()
        }
    }

    /** 場地座標（英寸）是否爲好球帶 */
    fun isInStrikeZone(xInches: Double, yInches: Double): Boolean {
        return abs(xInches) <= PLATE_WIDTH_IN / 2.0 &&
            yInches >= STRIKE_ZONE_BOTTOM_IN &&
            yInches <= STRIKE_ZONE_TOP_IN
    }

    /**
     * 三點外推：已知當前位置與速度，求到達 z=0（本壘板平面）時的落點與耗時。
     * @return (xIn, yIn, seconds)；z 方向速度爲 0 或已遠離時返回 null
     */
    fun projectToPlate(
        positionIn: Point3D,
        vxInPerSec: Double,
        vyInPerSec: Double,
        vzInPerSec: Double,
        plateDepthIn: Double = 0.0
    ): Triple<Double, Double, Double>? {
        if (abs(vzInPerSec) < 1e-3) return null
        val seconds = (plateDepthIn - positionIn.zInches) / vzInPerSec
        if (seconds <= 0.0 || seconds > 1.2) return null
        val x = positionIn.xInches + vxInPerSec * seconds
        val y = positionIn.yInches + vyInPerSec * seconds
        return Triple(x, y, seconds)
    }

    /** 最小二乘擬合斜率（y = slope*x + intercept），返回 slope */
    fun leastSquaresSlope(xs: DoubleArray, ys: DoubleArray): Double? {
        val n = minOf(xs.size, ys.size)
        if (n < 2) return null
        var meanX = 0.0
        var meanY = 0.0
        for (i in 0 until n) {
            meanX += xs[i]
            meanY += ys[i]
        }
        meanX /= n
        meanY /= n
        var numerator = 0.0
        var denominator = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            numerator += dx * (ys[i] - meanY)
            denominator += dx * dx
        }
        if (denominator <= 1e-9) return null
        return numerator / denominator
    }

    /** 兩條軌跡的水平位移差（英寸），Phase 2 用來算「尾段變化量」 */
    fun lateralBreakInches(actualPlateX: Double, straightLinePlateX: Double): Double =
        actualPlateX - straightLinePlateX

    /** 斜邊長度，供 UI 小工具使用 */
    fun distance2d(x0: Double, y0: Double, x1: Double, y1: Double): Double =
        sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
}

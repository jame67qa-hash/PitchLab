package com.pitchlab.domain.cv

import com.pitchlab.core.camera.CameraIntrinsics
import com.pitchlab.domain.model.FrameAnalysisResult
import com.pitchlab.domain.model.LivePitchMetrics
import com.pitchlab.domain.model.PlateLocation
import com.pitchlab.domain.model.Point3D
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

/**
 * 把逐幀球體觀測聚合成「一次投球」的物理指標。
 *
 * 數據流：
 *   FrameAnalysisResult(像素座標 + 像素半徑)
 *     -> 深度估計 Z
 *     -> 三維點 (X, Y, Z)
 *     -> 最小二乘擬合速度
 *     -> 外推到本壘板平面得到落點 + 位移
 *
 * 重要：單目深度估計對相機標定與球體像素半徑很敏感，
 * Phase 1 輸出建議在 UI 標註「估算」，Phase 2 用四點標定與真實球速對比校準。
 */
@Singleton
class PitchMetricsCalculator @Inject constructor(
    private val geometry: PerspectiveTransformCalculator,
    private val registry: CalibrationRegistry,
    private val strikeZone: StrikeZoneEvaluator,
    private val reconstructor: TrajectoryReconstructor
) {

    private companion object {
        /** 兩球之間超過該間隔視爲新的一球 */
        const val GAP_SEC = 0.65

        /** 參與擬合的最多樣本數（越大越平滑，但跟不上急變） */
        const val MAX_SAMPLES = 14

        const val MIN_SAMPLES_FOR_SPEED = 4

        const val MIN_DEPTH_IN = 15.0
        const val MAX_DEPTH_IN = 900.0

        /** 合理的業餘~職業球速範圍，超出即判爲誤檢 */
        const val MIN_PLAUSIBLE_MPH = 25.0
        const val MAX_PLAUSIBLE_MPH = 110.0

        /** 出手判定：接近相機（vz < 0）且速度達到閾值 */
        const val RELEASE_SPEED_THRESHOLD_MPH = 40.0
    }

    private data class TrackSample(
        val tSec: Double,
        val xIn: Double,
        val yIn: Double,
        val zIn: Double,
        val confidence: Double,
        val radiusPx: Double
    )

    private val samples = ArrayDeque<TrackSample>()
    private var lastTimestampSec = 0.0
    private var lastRadiusPx = 0.0
    private var completed: LivePitchMetrics? = null

    /**
     * 攝入一幀分析結果，返回當前這一球的實時指標。
     * 若無有效檢測，會在超時後結算上一球並寫入 [completed]。
     */
    fun ingest(result: FrameAnalysisResult, intrinsics: CameraIntrinsics?): LivePitchMetrics {
        val nowSec = result.timestampNanos / 1_000_000_000.0
        flushIfStale(nowSec)

        val ball = result.ball
        val camera = intrinsics
        if (ball == null || camera == null) {
            return buildMetrics(isFinal = false)
        }

        // 速度刻度校準：深度與速度同比例縮放（Z' = k·Z 讓 |v| 也乘上 k）。
        // k 由「雷達實測 / App 估算」得到，未校準時為 1.0。
        val depth = geometry.depthFromApparentRadius(ball.radiusPx, camera.focalLengthPxX) *
            registry.speedScale
        if (depth < MIN_DEPTH_IN || depth > MAX_DEPTH_IN) {
            return buildMetrics(isFinal = false)
        }

        val position: Point3D = geometry.projectToCamera3D(
            uPx = ball.xPx,
            vPx = ball.yPx,
            depthIn = depth,
            intrinsics = camera
        )

        samples.addLast(
            TrackSample(
                tSec = nowSec,
                xIn = position.xInches,
                yIn = position.yInches,
                zIn = position.zInches,
                confidence = ball.confidence,
                radiusPx = ball.radiusPx
            )
        )
        while (samples.size > MAX_SAMPLES) {
            samples.removeFirst()
        }

        lastTimestampSec = nowSec
        lastRadiusPx = ball.radiusPx

        return buildMetrics(isFinal = false)
    }

    /** 取走「已結算」的一球（UI 用於累計球數、寫數據庫），取走後清空 */
    fun consumeCompleted(): LivePitchMetrics? {
        val value = completed
        completed = null
        return value
    }

    /** 影片錄製結束時強制結算當前球 */
    fun finishCurrent(): LivePitchMetrics? {
        if (samples.isEmpty()) return null
        val metrics = buildMetrics(isFinal = true)
        completed = metrics
        samples.clear()
        return metrics
    }

    fun reset() {
        samples.clear()
        completed = null
        lastTimestampSec = 0.0
        lastRadiusPx = 0.0
    }

    private fun flushIfStale(nowSec: Double) {
        if (samples.isNotEmpty() && nowSec - lastTimestampSec > GAP_SEC) {
            completed = buildMetrics(isFinal = true)
            samples.clear()
        }
    }

    private fun buildMetrics(isFinal: Boolean): LivePitchMetrics {
        val count = samples.size
        val empty = LivePitchMetrics(trackedFrames = count)
        if (count < MIN_SAMPLES_FOR_SPEED) return empty

        val times = DoubleArray(count) { samples[it].tSec }
        val xs = DoubleArray(count) { samples[it].xIn }
        val ys = DoubleArray(count) { samples[it].yIn }
        val zs = DoubleArray(count) { samples[it].zIn }

        val vx = geometry.leastSquaresSlope(times, xs) ?: return LivePitchMetrics(trackedFrames = count)
        val vy = geometry.leastSquaresSlope(times, ys) ?: return LivePitchMetrics(trackedFrames = count)
        val vz = geometry.leastSquaresSlope(times, zs) ?: return LivePitchMetrics(trackedFrames = count)

        val speedMph = geometry.speedMphFromVelocity(vx, vy, vz)
        val speedValid = speedMph in MIN_PLAUSIBLE_MPH..MAX_PLAUSIBLE_MPH
        val releaseDetected = speedValid && vz < 0.0 && speedMph >= RELEASE_SPEED_THRESHOLD_MPH

        val last = samples.last()
        val current = Point3D(last.xIn, last.yIn, last.zIn)

        // 外推到 z = 0（本壘板平面）。相機若架在本壘板後方，z=0 略有偏差，
        // Phase 2 會用標定得到的 plateDepthIn 校正。
        val projected = if (speedValid) {
            geometry.projectToPlate(current, vx, vy, vz, plateDepthIn = 0.0)
        } else {
            null
        }

        val plate = projected?.let { PlateLocation(xInches = it.first, yInches = it.second) }
        val strike = strikeZone.evaluate(plate, registry.profile)

        val first = samples.first()
        val horizontalBreak = projected?.let { it.first - first.xIn }
        val verticalBreak = projected?.let { it.second - first.yIn }

        val avgConfidence = samples.sumOf { it.confidence } / count

        // Phase 10：用相機高度＋俯仰角做 3D 重建，取得放球高度與放球距離。
        // 取樣本序列的第一筆（離相機最遠、最接近出手點）。
        val setup = CameraSetup(
            heightIn = registry.profile.cameraHeightIn.takeIf { it > 0.0 }
                ?: com.pitchlab.domain.model.CalibrationProfile.DEFAULT_CAMERA_HEIGHT_IN,
            pitchDeg = registry.profile.cameraPitchDeg
        )
        val releasePoint = Point3D(first.xIn, first.yIn, first.zIn)
        val showRelease = isFinal || count >= 6
        val releaseHeight = reconstructor.releaseHeightFeet(releasePoint, setup).takeIf { showRelease }
        val releaseDistance = reconstructor.releaseDistanceFeet(releasePoint, setup).takeIf { showRelease }

        return LivePitchMetrics(
            trackedFrames = count,
            timeToPlateMillis = projected?.let { (it.third * 1000.0).toLong() },
            instantaneousMph = if (speedValid) speedMph else null,
            averageMph = if (speedValid) {
                geometry.releaseSpeedEstimateMph(speedMph)
            } else {
                null
            },
            releaseDetected = releaseDetected,
            horizontalBreakInches = horizontalBreak?.takeIf { isFinal || count >= 6 },
            verticalBreakInches = verticalBreak?.takeIf { isFinal || count >= 6 },
            plateLocation = plate,
            isStrike = strike?.isStrike,
            strikeHeightInches = strike?.heightInches,
            strikeHorizontalInches = strike?.horizontalInches,
            speedCalibrated = registry.profile.isSpeedCalibrated,
            confidence = avgConfidence.coerceIn(0.0, 1.0),
            releaseHeightFeet = releaseHeight,
            releaseDistanceFeet = releaseDistance
        )
    }

    /** 供 UI 用的球速顯示：優先瞬時值，退化到平均估計 */
    fun displaySpeed(metrics: LivePitchMetrics): Double? =
        metrics.instantaneousMph ?: metrics.averageMph

    /** 球體在畫面中的像素半徑，用於估算「球離相機多遠」的粗調提示 */
    fun lastRadius(): Double = lastRadiusPx

    /** 距離由半徑推得，越小表示球越近；用於判斷是否已接近本壘板 */
    fun apparentDistanceInches(intrinsics: CameraIntrinsics): Double =
        geometry.depthFromApparentRadius(lastRadiusPx, intrinsics.focalLengthPxX)

    /** 綜合速度與半徑的粗糙「接近率」指標，便於調試 UI 動效 */
    fun approachRateInchesPerSecond(intrinsics: CameraIntrinsics): Double {
        if (samples.size < 2) return 0.0
        val first = samples.first()
        val last = samples.last()
        val dt = last.tSec - first.tSec
        if (dt <= 0.0) return 0.0
        return hypot(last.xIn - first.xIn, last.zIn - first.zIn) / dt
    }
}

package com.pitchlab.domain.cv

import com.pitchlab.domain.model.BallDetection
import com.pitchlab.domain.model.DetectionSource
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.KalmanFilter
import org.opencv.video.Video
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 棒球追蹤器：MOG2 背景相減 / HSV 白色過濾 + 輪廓篩選 + Kalman 平滑。
 *
 * 設計約束：
 * - 實例內部持有 OpenCV Mat，**只允許在單一分析執行緒重用**（CameraX ImageAnalysis 的執行器）；
 * - 所有 Mat 顯式 release，避免 native 記憶體洩漏；
 * - 每次檢測都會走 Kalman，丟失若干幀時用預測值延續軌跡，超過閾值才判定軌跡結束。
 */
@Singleton
class BaseballTracker @Inject constructor() {

    private companion object {
        /** 輪廓面積下限：720p 下遠處球體約 40px² */
        const val MIN_CONTOUR_AREA = 35.0

        /** 輪廓面積上限：過濾掉看臺觀衆、球的整塊背景 */
        const val MAX_CONTOUR_AREA = 12000.0

        /** 圓度閾值 4πA/P²，棒球接近 1.0 */
        const val MIN_CIRCULARITY = 0.55

        /** 灰度均值下限：白球在多數球場光照下 > 110 */
        const val MIN_MEAN_BRIGHTNESS = 105.0

        /** 允許連續丟失的幀數（60fps 下約 200ms） */
        const val MAX_MISSED_FRAMES = 12

        /** 軌跡中斷閾值（毫秒），超過則重置 Kalman */
        const val TRACK_RESET_GAP_MS = 500
    }

    // ---------- 可重用的工作 Mat（避免每幀分配） ----------
    private val blurred = Mat()
    private val fgMask = Mat()
    private val binary = Mat()
    private val hsv = Mat()
    private val roiView = Mat()
    private val hierarchy = Mat()
    private val contourList = ArrayList<MatOfPoint>()
    private val contour2f = MatOfPoint2f()
    private val centerPoint = Point()
    private val radiusArray = FloatArray(1)

    private var subtractor: BackgroundSubtractorMOG2 =
        Video.createBackgroundSubtractorMOG2(300, 26.0, false)

    private val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))

    private var kalman: KalmanFilter? = null
    private var missedFrames = 0
    private var lastDetectionTimestampMs = 0.0
    private var lastKnown: BallDetection? = null

    /**
     * MOG2 運動檢測（預設路徑，輸入灰度圖）。
     *
     * @param gray          已轉正的灰度幀（CV_8UC1）
     * @param timestampMs   幀時間戳（毫秒，單調時鐘）
     */
    fun processMotion(gray: Mat, timestampMs: Double): TrackerOutput {
        if (gray.empty()) return TrackerOutput(null, 0, false)

        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        // learningRate 0.02：投手/捕手緩慢移動不會長期駐留在前景，但快速來球一定會被檢出
        subtractor.apply(blurred, fgMask, 0.02)

        Imgproc.threshold(fgMask, binary, 200.0, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)

        val candidates = extractCandidates(binary, gray)
        return finalizeDetection(candidates, timestampMs, DetectionSource.MOG2_MOTION)
    }

    /**
     * HSV 白色過濾（輸入 BGR 幀）。
     * 白球 -> 低飽和、高明度；在複雜背景下比運動檢測更穩，但需要彩色幀轉換，開銷更高。
     */
    fun processWhite(bgr: Mat, timestampMs: Double): TrackerOutput {
        if (bgr.empty()) return TrackerOutput(null, 0, false)

        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        Core.inRange(hsv, Scalar(0.0, 0.0, 175.0), Scalar(180.0, 55.0, 255.0), binary)
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)

        // 用灰度圖做亮度複覈，進一步壓掉白色廣告牌
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val candidates = extractCandidates(binary, gray)
        gray.release()

        return finalizeDetection(candidates, timestampMs, DetectionSource.HSV_WHITE)
    }

    /** 輪廓 -> 圓形候選（面積 / 圓度 / 亮度三重篩選） */
    private fun extractCandidates(mask: Mat, gray: Mat): List<Candidate> {
        contourList.forEach { it.release() }
        contourList.clear()
        hierarchy.release()
        Imgproc.findContours(
            mask, contourList, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        val result = ArrayList<Candidate>(contourList.size)
        for (contour in contourList) {
            val area = Imgproc.contourArea(contour)
            if (area < MIN_CONTOUR_AREA || area > MAX_CONTOUR_AREA) continue

            contour.convertTo(contour2f, CvType.CV_32F)
            if (contour2f.total() < 3.0) continue

            Imgproc.minEnclosingCircle(contour2f, centerPoint, radiusArray)
            val radius = radiusArray[0]
            if (radius <= 1.0f) continue

            val perimeter = Imgproc.arcLength(contour2f, true)
            if (perimeter <= 0.0) continue
            val circularity = (4.0 * PI * area) / (perimeter * perimeter)
            if (circularity < MIN_CIRCULARITY) continue

            val bound = Imgproc.boundingRect(contour)
            val aspect = bound.width.toDouble() / max(1, bound.height).toDouble()
            if (aspect < 0.55 || aspect > 1.8) continue

            // 亮度複覈：只取包圍盒內切區域，避免把深色邊緣算進去
            val inset = Rect(
                bound.x + bound.width / 4,
                bound.y + bound.height / 4,
                max(1, bound.width / 2),
                max(1, bound.height / 2)
            )
            inset.x = max(0, inset.x)
            inset.y = max(0, inset.y)
            inset.width = min(inset.width, gray.cols() - inset.x)
            inset.height = min(inset.height, gray.rows() - inset.y)
            if (inset.width <= 0 || inset.height <= 0) continue

            val roi = gray.submat(inset)
            val mean = Core.mean(roi)
            roi.release()
            if (mean.`val`[0] < MIN_MEAN_BRIGHTNESS) continue

            // 綜合評分：面積越接近理想球體、圓度越高，得分越高
            val score = circularity * 0.6 + (area / (PI * radius * radius)) * 0.4
            result.add(Candidate(centerPoint.x, centerPoint.y, radius.toDouble(), score))
        }
        return result
    }

    /** Kalman 預測 + 數據關聯 + 平滑輸出 */
    private fun finalizeDetection(
        candidates: List<Candidate>,
        timestampMs: Double,
        source: DetectionSource
    ): TrackerOutput {
        if (timestampMs - lastDetectionTimestampMs > TRACK_RESET_GAP_MS) {
            resetKalman()
        }

        val tracker = ensureKalman()
        val predicted = tracker.predict()
        val predictedState = DoubleArray(predicted.rows() * predicted.cols())
        predicted.get(0, 0, predictedState)
        val predictedX = predictedState[0]
        val predictedY = predictedState[1]

        val associationGate = if (missedFrames == 0) 3.0 else 2.0
        val best = candidates.minByOrNull { c ->
            hypot(c.x - predictedX, c.y - predictedY) - c.score * 8.0
        }

        val accepted = best?.takeIf { c ->
            val distance = hypot(c.x - predictedX, c.y - predictedY)
            distance <= max(c.radius * associationGate, 28.0)
        }

        return if (accepted != null) {
            val measurement = Mat(2, 1, CvType.CV_32F)
            measurement.put(0, 0, floatArrayOf(accepted.x.toFloat(), accepted.y.toFloat()))
            tracker.correct(measurement)
            measurement.release()

            val state = tracker.predict()
            val smoothed = FloatArray(4)
            state.get(0, 0, smoothed)

            missedFrames = 0
            lastDetectionTimestampMs = timestampMs

            val detection = BallDetection(
                xPx = smoothed[0].toDouble(),
                yPx = smoothed[1].toDouble(),
                radiusPx = accepted.radius,
                confidence = (accepted.score / 1.2).coerceIn(0.0, 1.0),
                frameWidth = binary.cols(),
                frameHeight = binary.rows()
            )
            lastKnown = detection
            TrackerOutput(detection, candidates.size, smoothed = true, source = source)
        } else {
            missedFrames++
            if (missedFrames > MAX_MISSED_FRAMES) {
                lastKnown = null
                resetKalman()
                TrackerOutput(null, candidates.size, smoothed = false, source = DetectionSource.NONE)
            } else {
                // 用預測值延續軌跡，confidence 隨丟失幀衰減
                val decay = 1.0 - missedFrames.toDouble() / (MAX_MISSED_FRAMES + 1)
                val known = lastKnown
                val carried = known?.copy(
                    xPx = predictedX,
                    yPx = predictedY,
                    confidence = (known.confidence * decay).coerceIn(0.0, 1.0)
                )
                TrackerOutput(
                    ball = carried,
                    candidateCount = candidates.size,
                    smoothed = true,
                    source = if (carried != null) source else DetectionSource.NONE,
                    carryingPrediction = true
                )
            }
        }
    }

    private fun ensureKalman(): KalmanFilter {
        kalman?.let { return it }

        val filter = KalmanFilter(4, 2, 0, CvType.CV_32F)

        val transition = Mat(4, 4, CvType.CV_32F)
        transition.put(
            0, 0,
            floatArrayOf(
                1f, 0f, 1f, 0f,
                0f, 1f, 0f, 1f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
            )
        )
        filter.set_transitionMatrix(transition)

        val measurement = Mat(2, 4, CvType.CV_32F)
        measurement.put(
            0, 0,
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f
            )
        )
        filter.set_measurementMatrix(measurement)

        // OpenCV 4.10 的 Java 綁定把這些成員暴露爲 get_xxx / set_xxx，
        // 這裏顯式構造 Mat 再注入，避免依賴 getter 返回值的共享語義。
        val processNoise = Mat.zeros(4, 4, CvType.CV_32F)
        Core.setIdentity(processNoise, Scalar.all(2e-2))
        filter.set_processNoiseCov(processNoise)

        val measurementNoise = Mat.zeros(2, 2, CvType.CV_32F)
        Core.setIdentity(measurementNoise, Scalar.all(1.2e-1))
        filter.set_measurementNoiseCov(measurementNoise)

        val errorCov = Mat.zeros(4, 4, CvType.CV_32F)
        Core.setIdentity(errorCov, Scalar.all(1.0))
        filter.set_errorCovPost(errorCov)

        // 初始狀態放在畫面中心，避免第一幀 predict 把數據關聯吸到 (0,0)
        val post = Mat(4, 1, CvType.CV_32F)
        post.put(0, 0, floatArrayOf(binary.cols() / 2f, binary.rows() / 2f, 0f, 0f))
        filter.set_statePost(post)

        kalman = filter
        missedFrames = MAX_MISSED_FRAMES // 首幀不做門控，直接接受最佳候選
        return filter
    }

    private fun resetKalman() {
        kalman = null
        missedFrames = 0
        lastKnown = null
    }

    /** 新的一場投球 / 切換鏡頭時重置背景模型，避免上一輪前景殘留 */
    fun reset() {
        resetKalman()
        lastDetectionTimestampMs = 0.0
        subtractor = Video.createBackgroundSubtractorMOG2(300, 26.0, false)
        fgMask.release()
        binary.release()
    }

    fun release() {
        blurred.release()
        fgMask.release()
        binary.release()
        hsv.release()
        roiView.release()
        hierarchy.release()
        contourList.forEach { it.release() }
        contourList.clear()
        contour2f.release()
        kernel.release()
        reset()
    }

    private data class Candidate(
        val x: Double,
        val y: Double,
        val radius: Double,
        val score: Double
    )
}

/** 追蹤器輸出 */
data class TrackerOutput(
    val ball: BallDetection?,
    val candidateCount: Int,
    val smoothed: Boolean,
    val source: DetectionSource = DetectionSource.NONE,
    val carryingPrediction: Boolean = false
)

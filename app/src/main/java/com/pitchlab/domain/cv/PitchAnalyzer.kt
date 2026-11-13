package com.pitchlab.domain.cv

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.pitchlab.core.camera.CameraIntrinsics
import com.pitchlab.core.cv.OpenCvNativeLoader
import com.pitchlab.core.cv.YuvPlaneExtractor
import com.pitchlab.core.cv.YuvToRgbConverter
import com.pitchlab.domain.model.BallDetection
import com.pitchlab.domain.model.DetectionSource
import com.pitchlab.domain.model.FrameAnalysisResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraX 的逐幀分析器 —— Phase 1 的 CV 入口。
 *
 * 職責：
 * 1. 把 YUV_420_888 的 Y plane 轉成灰度 Mat（不經過 Bitmap，省一次全圖轉換）；
 * 2. 按 imageInfo.rotationDegrees 把幀轉正，保證像素座標與 UI 疊加層一致；
 * 3. 交給 [BaseballTracker] 做 MOG2 / HSV 檢測 + Kalman 平滑；
 * 4. 通過 SharedFlow 把「純數據」拋給 ViewModel，UI 層不接觸 OpenCV；
 * 5. 按需（抓幀 / MediaPipe）把同一幀轉成 Bitmap。
 *
 * 執行緒模型：所有 Mat 只在 CameraX 的單一分析執行緒上讀寫，無需加鎖；
 * 與外部交互的狀態用 Atomic 包裝。
 */
class PitchAnalyzer(
    private val tracker: BaseballTracker,
    private val yuvToRgbConverter: YuvToRgbConverter
) : ImageAnalysis.Analyzer {

    private companion object {
        const val TAG = "PitchAnalyzer"

        /** 處理解析度上限：超過則等比縮小，保證 60fps 下每幀 < 10ms */
        const val MAX_PROCESS_WIDTH = 720
    }

    private val _results = MutableSharedFlow<FrameAnalysisResult>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val results: SharedFlow<FrameAnalysisResult> = _results

    private val intrinsicsRef = AtomicReference<CameraIntrinsics?>(null)
    private val colorModeEnabled = AtomicBoolean(false)
    private val pendingDebugFrames = AtomicInteger(0)
    private val processingEnabled = AtomicBoolean(true)

    /** 抓幀回調：Phase 2 用於「凍結當前幀」標定 */
    var frameListener: ((Bitmap, Long) -> Unit)? = null

    /**
     * 姿態推論回調（Phase 3）：每 [poseInterval] 幀輸出一張 upright Bitmap。
     *
     * 之所以跟 [frameListener] 分開：標定要「精確一幀且可暫停」，出手偵測要「持續、
     * 可降頻」；兩者生命週期不同，混在同一個 slot 會互相覆蓋。
     */
    var poseListener: ((Bitmap, Long) -> Unit)? = null
    private val poseInterval = AtomicInteger(3)
    private var poseFrameCounter = 0L

    fun setPoseInterval(frames: Int) {
        poseInterval.set(frames.coerceAtLeast(0))
        poseFrameCounter = 0L
    }

    val isPoseEnabled: Boolean get() = poseListener != null && poseInterval.get() > 0

    // ---------- 分析執行緒獨佔的可重用緩衝 ----------
    // 延遲建立：OpenCV native 庫若未載入，也不會在建構時崩潰
    private val grayMat: Mat by lazy { Mat() }
    private val uprightMat: Mat by lazy { Mat() }
    private val scaledMat: Mat by lazy { Mat() }
    private val rgbaMat: Mat by lazy { Mat() }
    private val bgrMat: Mat by lazy { Mat() }
    private var rowBuffer = ByteArray(0)
    private var bitmapScratch: Bitmap? = null

    private var frameIndex = 0L
    private var lastTimestampNs = 0L
    private var fpsEma = 0.0
    private var lastFrameWidth = 0
    private var lastFrameHeight = 0
    private var lastRotation = 0

    fun updateIntrinsics(intrinsics: CameraIntrinsics?) {
        intrinsicsRef.set(intrinsics)
    }

    fun setColorModeEnabled(enabled: Boolean) {
        colorModeEnabled.set(enabled)
        if (enabled) {
            // 白色過濾不需要背景模型，重置一次避免舊前景干擾
            tracker.reset()
        }
    }

    fun setProcessingEnabled(enabled: Boolean) {
        processingEnabled.set(enabled)
        if (!enabled) tracker.reset()
    }

    fun requestDebugFrame() {
        pendingDebugFrames.incrementAndGet()
    }

    fun reset() {
        tracker.reset()
        frameIndex = 0
        lastTimestampNs = 0
        fpsEma = 0.0
    }

    /**
     * 轉出調試幀。
     *
     * 兩個關鍵點：
     * 1) converter 給的是感測器原始方向的 Bitmap，必須依 rotationDegrees 轉正，
     *    才會和「分析用的 upright 座標系」重合（好球帶與角點都建立在後者）；
     * 2) 這裡不 copy，重用與否由呼叫端負責（UI 端會 copy 一份）。
     */
    private fun deliverDebugFrameIfRequested(image: ImageProxy) {
        if (pendingDebugFrames.get() <= 0) return
        if (!OpenCvNativeLoader.isLoaded()) return
        pendingDebugFrames.decrementAndGet()
        try {
            val bitmap = rotateUpright(
                yuvToRgbConverter.toBitmap(image),
                image.imageInfo.rotationDegrees
            )
            frameListener?.invoke(bitmap, SystemClock.elapsedRealtimeNanos())
        } catch (t: Throwable) {
            Log.e(TAG, "deliverDebugFrame failed", t)
        }
    }

    private fun rotateUpright(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun analyze(image: ImageProxy) {
        if (!processingEnabled.get()) {
            // 標定模式會關閉分析，但仍要能交出「凍結的一幀」給使用者點角點，
            // 否則 requestDebugFrame() 永遠等不到 Bitmap。
            deliverDebugFrameIfRequested(image)
            image.close()
            return
        }

        val startNs = SystemClock.elapsedRealtimeNanos()
        var detection: BallDetection? = null
        var source = DetectionSource.NONE
        var candidateCount = 0
        var width = 0
        var height = 0
        var rotation = 0
        var processingMs = 0.0
        var resultToEmit: FrameAnalysisResult? = null

        try {
            if (!OpenCvNativeLoader.isLoaded()) {
                throw IllegalStateException("OpenCV native library 未載入")
            }

            width = image.width
            height = image.height
            rotation = image.imageInfo.rotationDegrees

            // 1) Y plane -> 灰度 Mat
            val pair = YuvPlaneExtractor.yPlaneToGrayMat(image, grayMat, rowBuffer)
            rowBuffer = pair.second
            val gray = pair.first

            // 2) 轉正
            val upright = rotateUpright(gray, rotation)

            // 3) 必要時縮小到處理解析度
            val scaled = downscaleIfNeeded(upright)

            // 4) 檢測
            val timestampNs = SystemClock.elapsedRealtimeNanos()
            val timestampMs = timestampNs / 1_000_000.0

            val output = if (colorModeEnabled.get()) {
                runColorDetection(image, timestampMs)
            } else {
                tracker.processMotion(scaled, timestampMs)
            }

            detection = output.ball
            source = if (detection != null) output.source else DetectionSource.NONE
            candidateCount = output.candidateCount

            lastFrameWidth = scaled.cols()
            lastFrameHeight = scaled.rows()
            lastRotation = rotation

            // 5) FPS 估計（EMA，避免抖動）
            if (lastTimestampNs != 0L) {
                val deltaNs = timestampNs - lastTimestampNs
                if (deltaNs > 0L) {
                    val instant = 1_000_000_000.0 / deltaNs
                    fpsEma = if (fpsEma == 0.0) instant else fpsEma * 0.85 + instant * 0.15
                }
            }
            lastTimestampNs = timestampNs

            // 6a) 姿態推論取幀（降頻，避免吃掉分析執行緒）
            val interval = poseInterval.get()
            if (poseListener != null && interval > 0 && poseFrameCounter % interval == 0L) {
                try {
                    val poseBitmap = rotateUpright(yuvToRgbConverter.toBitmap(image), rotation)
                    poseListener?.invoke(poseBitmap, timestampNs)
                } catch (t: Throwable) {
                    Log.e(TAG, "pose frame delivery failed", t)
                }
            }
            poseFrameCounter++

            // 6b) 按需輸出 Bitmap（調試抓幀 / MediaPipe）
            if (pendingDebugFrames.get() > 0) {
                pendingDebugFrames.decrementAndGet()
                val bitmap = rotateUpright(yuvToRgbConverter.toBitmap(image), rotation)
                frameListener?.invoke(bitmap, timestampNs)
            }

            processingMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
            frameIndex++

            resultToEmit = FrameAnalysisResult(
                frameIndex = frameIndex,
                timestampNanos = timestampNs,
                width = lastFrameWidth,
                height = lastFrameHeight,
                rotationDegrees = lastRotation,
                ball = detection,
                source = source,
                processingMillis = processingMs,
                measuredFps = fpsEma,
                candidateContours = candidateCount
            )
        } catch (t: Throwable) {
            Log.e(TAG, "analyze failed at frame $frameIndex", t)
            val timestampNs = SystemClock.elapsedRealtimeNanos()
            frameIndex++
            resultToEmit = FrameAnalysisResult(
                frameIndex = frameIndex,
                timestampNanos = timestampNs,
                width = lastFrameWidth,
                height = lastFrameHeight,
                rotationDegrees = lastRotation,
                ball = null,
                source = DetectionSource.NONE,
                processingMillis = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0,
                measuredFps = fpsEma,
                candidateContours = 0
            )
        } finally {
            // 必須無條件關閉，否則 CameraX 緩衝區耗盡會直接停流
            image.close()
            resultToEmit?.let { _results.tryEmit(it) }
        }
    }

    /** HSV 白色過濾路徑：需要一張色彩幀，走 Bitmap -> RGBA -> BGR */
    private fun runColorDetection(image: ImageProxy, timestampMs: Double): TrackerOutput {
        val bitmap = yuvToRgbConverter.toBitmap(image)
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, bgrMat, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return tracker.processWhite(bgrMat, timestampMs)
    }

    private fun rotateUpright(src: Mat, rotationDegrees: Int): Mat {
        when (rotationDegrees) {
            90 -> Core.rotate(src, uprightMat, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, uprightMat, Core.ROTATE_180)
            270 -> Core.rotate(src, uprightMat, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return src
        }
        return uprightMat
    }

    private fun downscaleIfNeeded(src: Mat): Mat {
        if (src.cols() <= MAX_PROCESS_WIDTH) return src
        val scale = MAX_PROCESS_WIDTH.toDouble() / src.cols()
        val targetWidth = MAX_PROCESS_WIDTH
        val targetHeight = (src.rows() * scale).toInt().coerceAtLeast(1)
        Imgproc.resize(src, scaledMat, org.opencv.core.Size(targetWidth.toDouble(), targetHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        return scaledMat
    }

    fun currentIntrinsics(): CameraIntrinsics? = intrinsicsRef.get()

    fun release() {
        grayMat.release()
        uprightMat.release()
        scaledMat.release()
        rgbaMat.release()
        bgrMat.release()
        bitmapScratch?.recycle()
        bitmapScratch = null
        tracker.release()
        yuvToRgbConverter.release()
        frameListener = null
        poseListener = null
    }
}

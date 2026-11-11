package com.pitchlab.domain.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.WorkerThread
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe Pose 的薄封裝：只回傳「比較可信的那隻手腕」。
 *
 * 設計取捨：
 * - 用 [RunningMode.IMAGE] 而不是 LIVE_STREAM：Live stream 需要額外執行緒與時間戳單調性，
 *   而我們的幀本來就來自分析執行緒、且已自帶時間戳；IMAGE 模式同步回傳，邏輯最單純。
 * - 模型（`pose_landmarker_lite.task`，5.6 MB）放在 assets，離線可用，不在執行期下載。
 * - 初始化失敗（模型缺失、ABI 不支援）時 [available] 回 false，App 仍可正常追蹤球，
 *   只是沒有出手偵測與自動剪輯——這是刻意設計的降級，不是隱性崩潰。
 */
@Singleton
class PoseLandmarkerDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "PoseLandmarker"
        const val MODEL_ASSET = "pose_landmarker_lite.task"

        /** MediaPipe Pose 33 點索引：左腕 15、右腕 16 */
        private const val LEFT_WRIST = 15
        private const val RIGHT_WRIST = 16
    }

    /** 一次姿態推論的手腕結果（歸一化影像座標 + 可見度） */
    data class WristPoint(
        val x: Double,
        val y: Double,
        val visibility: Double
    )

    private var landmarker: PoseLandmarker? = null
    private var initAttempted = false
    private var initError: String? = null

    /** 是否可用（第一次呼叫時嘗試初始化） */
    val available: Boolean
        @Synchronized get() {
            ensureInitialized()
            return landmarker != null
        }

    val lastError: String? get() = initError

    @Synchronized
    private fun ensureInitialized() {
        if (landmarker != null || initAttempted) return
        initAttempted = true
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            landmarker = PoseLandmarker.createFromOptions(context, options)
            Log.i(TAG, "PoseLandmarker 初始化完成")
        } catch (t: Throwable) {
            initError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "PoseLandmarker 初始化失敗，出手偵測與自動剪輯將停用", t)
        }
    }

    /**
     * 對單一幀做姿態推論，回傳可見度較高的手腕。
     * 必須在背景執行緒呼叫（IMAGE 模式是同步阻塞的）。
     */
    @WorkerThread
    fun detectWrist(bitmap: Bitmap, timestampMs: Long): WristPoint? {
        ensureInitialized()
        val detector = landmarker ?: return null
        return try {
            val image = BitmapImageBuilder(bitmap).build()
            val result = detector.detect(image)
            val landmarks = result.landmarks().firstOrNull() ?: return null

            val left = landmarks.getOrNull(LEFT_WRIST)
            val right = landmarks.getOrNull(RIGHT_WRIST)
            val leftVisibility = left?.visibility()?.orElse(0f) ?: 0f
            val rightVisibility = right?.visibility()?.orElse(0f) ?: 0f
            val chosen = if (leftVisibility >= rightVisibility) left else right
            if (chosen == null) return null
            val vis = if (leftVisibility >= rightVisibility) leftVisibility else rightVisibility
            if (vis < 0.3f) return null
            WristPoint(
                x = chosen.x().toDouble(),
                y = chosen.y().toDouble(),
                visibility = vis.toDouble()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "detectWrist 失敗於 $timestampMs", t)
            null
        }
    }

    @Synchronized
    fun close() {
        runCatching { landmarker?.close() }
        landmarker = null
        initAttempted = false
    }
}

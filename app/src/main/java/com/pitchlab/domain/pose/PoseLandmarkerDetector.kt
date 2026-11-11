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
 * MediaPipe Pose 的薄封裝：回傳一幀姿勢的關鍵點（肩／肘／腕／髖／踝）。
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

        /** Phase 4 評分需要的關節：肩 11/12、肘 13/14、腕 15/16、髖 23/24、踝 27/28 */
        private val SCORING_LANDMARKS = intArrayOf(
            PoseIndices.LEFT_SHOULDER, PoseIndices.RIGHT_SHOULDER,
            PoseIndices.LEFT_ELBOW, PoseIndices.RIGHT_ELBOW,
            PoseIndices.LEFT_WRIST, PoseIndices.RIGHT_WRIST,
            PoseIndices.LEFT_HIP, PoseIndices.RIGHT_HIP,
            PoseIndices.LEFT_ANKLE, PoseIndices.RIGHT_ANKLE
        )
    }

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
     * 對單一幀做姿態推論，回傳評分需要的關節點。
     * 必須在背景執行緒呼叫（IMAGE 模式是同步阻塞的）。
     */
    @WorkerThread
    fun detectPose(bitmap: Bitmap, timestampMs: Long): PoseFrame? {
        ensureInitialized()
        val detector = landmarker ?: return null
        return try {
            val image = BitmapImageBuilder(bitmap).build()
            val result = detector.detect(image)
            val landmarks = result.landmarks().firstOrNull() ?: return null

            val points = HashMap<Int, PosePoint>(SCORING_LANDMARKS.size)
            for (index in SCORING_LANDMARKS) {
                val landmark = landmarks.getOrNull(index) ?: continue
                points[index] = PosePoint(
                    x = landmark.x().toDouble(),
                    y = landmark.y().toDouble(),
                    visibility = (landmark.visibility()?.orElse(0f) ?: 0f).toDouble()
                )
            }
            if (points.isEmpty()) return null
            PoseFrame(timestampMs = timestampMs, points = points)
        } catch (t: Throwable) {
            Log.e(TAG, "detectPose 失敗於 $timestampMs", t)
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

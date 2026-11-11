package com.pitchlab.domain.pose

/**
 * 一幀姿態的關鍵點（歸一化影像座標，左上為原點、y 向下）。
 * 刻意不含任何 Android / MediaPipe 型別，讓姿勢評分可在 JVM 單元測試裡驗證。
 */
data class PosePoint(
    val x: Double,
    val y: Double,
    val visibility: Double
)

/** MediaPipe Pose 33 點的索引常數（只用評分需要的部位） */
object PoseIndices {
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
}

/** 一次推論的姿態快照；只保留評分需要的關節點，避免每次配置 33 個物件 */
data class PoseFrame(
    val timestampMs: Long,
    val points: Map<Int, PosePoint>
) {
    fun point(index: Int): PosePoint? = points[index]
}

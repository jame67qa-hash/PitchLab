package com.pitchlab.domain.export

import com.pitchlab.domain.model.PitchSession

/** 3D 放球散點圖上的一個點：距離本壘多遠、放球多高 */
data class ReleasePoint(
    val distanceFt: Double,
    val heightFt: Double,
    val isStrike: Boolean?
)

/**
 * 3D 放球分佈的純資料層（不碰 Bitmap，才能在 JVM 單元測試裡驗證）。
 *
 * 只收「兩個值都在合理範圍」的球：Phase 10 已經擋過一次，但匯出時可能混進
 * 舊版（Phase 9 以前）沒有這個欄位的紀錄，所以這裡再過濾一次，
 * 不讓 0 或 999 這種值把座標軸撐壞。
 */
object ReleaseScatter {

    const val MIN_HEIGHT_FT = 2.0
    const val MAX_HEIGHT_FT = 12.0
    const val MIN_DISTANCE_FT = 30.0
    const val MAX_DISTANCE_FT = 70.0

    /** 繪圖用的固定視窗：多數業餘投手的放球點都落在這個範圍內 */
    const val VIEW_MIN_DISTANCE_FT = 40.0
    const val VIEW_MAX_DISTANCE_FT = 62.0
    const val VIEW_MIN_HEIGHT_FT = 2.0
    const val VIEW_MAX_HEIGHT_FT = 9.0

    fun pointsOf(sessions: List<PitchSession>): List<ReleasePoint> = sessions
        .asSequence()
        .mapNotNull { session ->
            val height = session.releaseHeightFt ?: return@mapNotNull null
            val distance = session.releaseDistanceFt ?: return@mapNotNull null
            if (height !in MIN_HEIGHT_FT..MAX_HEIGHT_FT) return@mapNotNull null
            if (distance !in MIN_DISTANCE_FT..MAX_DISTANCE_FT) return@mapNotNull null
            ReleasePoint(distance, height, session.isStrike)
        }
        .toList()

    /** 回傳 0..1 的正規化座標（超出視窗就夾在邊界，不畫到框外） */
    fun normalizeX(distanceFt: Double): Float {
        val ratio = (distanceFt - VIEW_MIN_DISTANCE_FT) /
            (VIEW_MAX_DISTANCE_FT - VIEW_MIN_DISTANCE_FT)
        return ratio.coerceIn(0.0, 1.0).toFloat()
    }

    fun normalizeY(heightFt: Double): Float {
        val ratio = (heightFt - VIEW_MIN_HEIGHT_FT) /
            (VIEW_MAX_HEIGHT_FT - VIEW_MIN_HEIGHT_FT)
        return ratio.coerceIn(0.0, 1.0).toFloat()
    }
}

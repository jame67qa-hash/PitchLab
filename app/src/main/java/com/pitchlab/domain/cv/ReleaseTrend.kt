package com.pitchlab.domain.cv

import com.pitchlab.domain.model.PitchSession

/** 一個出手點樣本（依時間舊 -> 新排列，index 從 1 開始） */
data class ReleasePoint(
    val index: Int,
    val heightFt: Double,
    val distanceFt: Double?,
    val startedAtMillis: Long,
    /** 同一球的球速；null = 該球沒有速度資料 */
    val speedMph: Double? = null
)

/**
 * 出手點隨球數的趨勢（Phase 16）。
 *
 * 為什麼看「高度」而不是球速：投手疲勞時最先崩的是放球點（掉臂、身體提前開），
 * 球速往往還撐得住；把近 N 球的高度排成折線，掉臂會很明顯。
 */
data class ReleaseTrendReport(
    val points: List<ReleasePoint> = emptyList(),
    /** 每多投一球，放球高度的平均變化（英尺／球） */
    val slopePerPitch: Double = 0.0,
    val averageHeightFt: Double? = null,
    val averageDistanceFt: Double? = null,
    /** 同一段球序的平均球速與球速斜率（mph／球），用來對照掉臂與掉速 */
    val averageSpeedMph: Double? = null,
    val speedSlopePerPitch: Double = 0.0
) {
    val hasData: Boolean get() = points.size >= 3
    val firstHeightFt: Double? get() = points.firstOrNull()?.heightFt
    val lastHeightFt: Double? get() = points.lastOrNull()?.heightFt
    /** 最後一球相對第一球的高度差（負 = 越來越低） */
    val deltaFt: Double? get() {
        val first = firstHeightFt ?: return null
        val last = lastHeightFt ?: return null
        return last - first
    }

    /** 趨勢判讀門檻：0.08 ft/球，10 球約 0.8 ft，才值得提醒 */
    /**
     * 判讀順序：先看「放球點＋球速同步下滑」這種最像疲勞的組合，
     * 再看單一訊號。球速斜率門檻 0.3 mph/球，10 球約 3 mph 才提醒。
     */
    val verdict: String get() = when {
        !hasData -> "至少 3 球才畫得出趨勢"
        slopePerPitch <= -0.08 && speedSlopePerPitch <= -0.3 ->
            "放球點與球速同步下滑，疲勞可能性高"
        slopePerPitch <= -0.08 -> "放球點逐步下墜，留意疲勞或掉臂"
        speedSlopePerPitch <= -0.3 -> "放球點還穩，但球速在下滑"
        slopePerPitch >= 0.08 -> "放球點逐步上揚，可能身體太早挺直"
        else -> "放球點大致穩定"
    }

    companion object {
        val EMPTY = ReleaseTrendReport()
    }
}

object ReleaseTrend {

    /** 預設看最近 20 球：再多，折線在手機上會擠成一團 */
    const val DEFAULT_LIMIT = 20
    const val MIN_POINTS = 3

    fun from(sessions: List<PitchSession>, limit: Int = DEFAULT_LIMIT): ReleaseTrendReport {
        // observeRecent 是 started_at DESC；先取最新 N 筆再反轉成舊 -> 新
        val usable = sessions
            .asSequence()
            .take(limit)
            .filter { it.releaseHeightFt != null }
            .toList()
            .asReversed()

        if (usable.size < MIN_POINTS) return ReleaseTrendReport(points = usable.mapIndexed { index, s ->
            ReleasePoint(index + 1, s.releaseHeightFt!!, s.releaseDistanceFt, s.startedAtMillis, s.fastestMph)
        })

        val points = usable.mapIndexed { index, session ->
            ReleasePoint(
                index = index + 1,
                heightFt = session.releaseHeightFt!!,
                distanceFt = session.releaseDistanceFt,
                startedAtMillis = session.startedAtMillis,
                speedMph = session.fastestMph
            )
        }
        val heights = points.map { it.heightFt }
        val slope = slopeOf(points)
        val distances = points.mapNotNull { it.distanceFt }
        val speeds = points.mapNotNull { it.speedMph }
        // 球速斜率只在速度樣本夠多時才算，否則回 0（不觸發提醒）
        val speedSlope = if (speeds.size >= MIN_POINTS) slopeOf(points.filter { it.speedMph != null }) {
            it.speedMph!!
        } else 0.0
        return ReleaseTrendReport(
            points = points,
            slopePerPitch = slope,
            averageHeightFt = heights.average(),
            averageDistanceFt = distances.takeIf { it.isNotEmpty() }?.average(),
            averageSpeedMph = speeds.takeIf { it.isNotEmpty() }?.average(),
            speedSlopePerPitch = speedSlope
        )
    }

    /** 最小平方直線斜率；x 用球序而不是時間戳，避免錄影間隔不均把斜率拉歪 */
    private fun slopeOf(
        points: List<ReleasePoint>,
        value: (ReleasePoint) -> Double = { it.heightFt }
    ): Double {
        val n = points.size
        if (n < 2) return 0.0
        val meanX = points.sumOf { it.index.toDouble() } / n
        val meanY = points.sumOf { value(it) } / n
        var numerator = 0.0
        var denominator = 0.0
        points.forEach { point ->
            val dx = point.index - meanX
            numerator += dx * (value(point) - meanY)
            denominator += dx * dx
        }
        return if (denominator <= 0.0) 0.0 else numerator / denominator
    }
}

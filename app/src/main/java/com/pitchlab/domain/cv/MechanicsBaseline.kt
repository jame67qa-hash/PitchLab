package com.pitchlab.domain.cv

import com.pitchlab.domain.model.PitchSession
import kotlin.math.abs

/**
 * 個人化投球機制基準線。
 *
 * 為什麼需要它：Phase 4 的四項指標用的是「通用理想區間」（例如肩髖分離 20–45°），
 * 對不同體型、不同投球習慣的人來說，同一個 28° 可能一個人是穩定、另一個人是過度扭轉。
 * 通用區間只能回答「動作像不像教科書」，回答不了「你今天的動作跟平常比起來怎樣」。
 * 因此這裡另外算一組**自己的**基準：用歷史資料的中位數（不是平均數），
 * 避免單一球姿勢偵測失敗（例如手腕被遮住）把基準拉歪。
 *
 * 樣本不足 [MIN_SAMPLES] 球時仍會算出數值，但 `isUsable == false`，
 * UI 必須顯示「樣本不足」，不能拿 2 球的「基準」去教使用者怎麼投。
 */
data class MechanicsBaseline(
    val sampleCount: Int,
    val separationDeg: Double? = null,
    val elbowDeg: Double? = null,
    val trunkTiltDeg: Double? = null,
    val releaseHeightRatio: Double? = null,
    val averageScore: Double? = null
) {
    val isUsable: Boolean get() = sampleCount >= MechanicsBaselineCalculator.MIN_SAMPLES
}

/** 單一次出手的四項指標快照（可從 CameraViewModel 的 currentMechanics 或最後一筆紀錄取得） */
data class MechanicsSnapshot(
    val separationDeg: Double? = null,
    val elbowDeg: Double? = null,
    val trunkTiltDeg: Double? = null,
    val releaseHeightRatio: Double? = null
)

/**
 * 一項指標與個人基準的差距。
 *
 * @param significant 超過該指標的實務容許誤差（肩髖 5°、肘 8°、前傾 5°、放球高度 0.05），
 *                    只有 true 的項目才值得在 UI 上提醒，避免每次都在「差 0.3°」上做文章。
 */
data class BaselineDelta(
    val label: String,
    val current: Double,
    val baseline: Double,
    val delta: Double,
    val unit: String,
    val significant: Boolean
) {
    /** 「+4°」「−0.03」這種帶正負號的字串，固定小數位 */
    fun deltaText(decimals: Int = 0): String {
        val rounded = if (decimals == 0) delta.toInt().toDouble() else delta
        val sign = if (rounded >= 0) "+" else "−"
        val magnitude = if (decimals == 0) {
            abs(rounded).toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.${decimals}f", abs(rounded))
        }
        return "$sign$magnitude$unit"
    }
}

object MechanicsBaselineCalculator {

    /** 少於 5 球的中位數沒有統計意義，UI 一律當作「樣本不足」 */
    const val MIN_SAMPLES = 5

    private const val SEPARATION_TOLERANCE = 5.0
    private const val ELBOW_TOLERANCE = 8.0
    private const val TRUNK_TOLERANCE = 5.0
    private const val HEIGHT_TOLERANCE = 0.05

    fun from(sessions: List<PitchSession>): MechanicsBaseline {
        // 只取有姿勢評分的球：沒偵測到人的球不該進入基準
        val scored = sessions.filter { it.poseScore != null }
        return MechanicsBaseline(
            sampleCount = scored.size,
            separationDeg = median(scored.mapNotNull { it.separationDeg }),
            elbowDeg = median(scored.mapNotNull { it.elbowDeg }),
            trunkTiltDeg = median(scored.mapNotNull { it.trunkTiltDeg }),
            releaseHeightRatio = median(scored.mapNotNull { it.releaseHeightRatio }),
            averageScore = if (scored.isEmpty()) null else scored.mapNotNull { it.poseScore }.average()
        )
    }

    fun snapshotOf(session: PitchSession): MechanicsSnapshot = MechanicsSnapshot(
        separationDeg = session.separationDeg,
        elbowDeg = session.elbowDeg,
        trunkTiltDeg = session.trunkTiltDeg,
        releaseHeightRatio = session.releaseHeightRatio
    )

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /**
     * 比較一次出手與個人基準。只回傳「兩邊都有值」的指標，
     * 缺少資料時不補 0，否則會產生「你的前傾角是 0°」這種假提醒。
     */
    fun deviations(snapshot: MechanicsSnapshot, baseline: MechanicsBaseline): List<BaselineDelta> {
        val result = mutableListOf<BaselineDelta>()
        compare(result, "肩髖分離", snapshot.separationDeg, baseline.separationDeg, "°", SEPARATION_TOLERANCE)
        compare(result, "前導手肘", snapshot.elbowDeg, baseline.elbowDeg, "°", ELBOW_TOLERANCE)
        compare(result, "軀幹前傾", snapshot.trunkTiltDeg, baseline.trunkTiltDeg, "°", TRUNK_TOLERANCE)
        compare(
            result,
            "放球高度",
            snapshot.releaseHeightRatio,
            baseline.releaseHeightRatio,
            "",
            HEIGHT_TOLERANCE
        )
        return result
    }

    private fun compare(
        target: MutableList<BaselineDelta>,
        label: String,
        current: Double?,
        base: Double?,
        unit: String,
        tolerance: Double
    ) {
        if (current == null || base == null) return
        val delta = current - base
        target += BaselineDelta(
            label = label,
            current = current,
            baseline = base,
            delta = delta,
            unit = unit,
            significant = abs(delta) > tolerance
        )
    }
}

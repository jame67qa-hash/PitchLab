package com.pitchlab.domain.cv

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 進壘點樣本（英寸）。刻意不用 UI 層的 PlateMark，
 * 讓控球分析維持在 domain 層、可以純 JVM 測試。
 */
data class PlateSample(
    val xInches: Double,
    val yInches: Double,
    val isStrike: Boolean?
)

/** 九宮格其中一格；row 0 是最上排（高球），column 0 是最左側 */
data class ZoneCell(
    val column: Int,
    val row: Int,
    val count: Int,
    val strikeCount: Int,
    val ballCount: Int
) {
    val strikeRate: Double?
        get() = if (strikeCount + ballCount == 0) null else strikeCount.toDouble() / (strikeCount + ballCount)
}

/**
 * 控球報告。
 *
 * 為什麼「散佈半徑」比「好球率」更能看進步：
 * 好球率只回答「進不進好球帶」，但一個投手可能靠著亂飄剛好塞進一半；
 * 散佈半徑（各球到平均落點的 RMS 距離）直接量化「投得準不準」，
 * 同一種球路連續 10 球的散佈縮小，就是控球變好，與裁判的好壞球判定無關。
 */
data class ControlReport(
    val sampleCount: Int,
    val outsideZoneCount: Int,
    val cells: List<ZoneCell>,
    val centroidXInches: Double?,
    val centroidYInches: Double?,
    val spreadRadiusInches: Double?,
    val score: Int?,
    val grade: String
) {
    val hasData: Boolean get() = sampleCount > 0

    companion object {
        val EMPTY = ControlReport(0, 0, emptyList(), null, null, null, null, "—")
    }
}

object ControlAnalyzer {

    const val ZONE_LEFT_IN = -8.5
    const val ZONE_RIGHT_IN = 8.5
    const val ZONE_BOTTOM_IN = 19.0
    const val ZONE_TOP_IN = 42.0
    const val GRID = 3

    /** 好球帶中心（x=0、高度 30.5 in）：用來描述「平均落點偏哪裡」 */
    const val ZONE_CENTER_X_IN = 0.0
    const val ZONE_CENTER_Y_IN = 30.5

    private val cellWidth = (ZONE_RIGHT_IN - ZONE_LEFT_IN) / GRID
    private val cellHeight = (ZONE_TOP_IN - ZONE_BOTTOM_IN) / GRID

    /** 回傳 0..8；落在好球帶外回傳 null（框外的球不硬塞進邊角，避免熱區失真） */
    fun cellIndex(xInches: Double, yInches: Double): Int? {
        if (xInches < ZONE_LEFT_IN || xInches > ZONE_RIGHT_IN) return null
        if (yInches < ZONE_BOTTOM_IN || yInches > ZONE_TOP_IN) return null
        val column = floor((xInches - ZONE_LEFT_IN) / cellWidth).toInt().coerceIn(0, GRID - 1)
        val row = floor((ZONE_TOP_IN - yInches) / cellHeight).toInt().coerceIn(0, GRID - 1)
        return row * GRID + column
    }

    fun analyze(samples: List<PlateSample>): ControlReport {
        if (samples.isEmpty()) return ControlReport.EMPTY

        val counts = IntArray(GRID * GRID)
        val strikes = IntArray(GRID * GRID)
        val balls = IntArray(GRID * GRID)
        var outside = 0
        for (sample in samples) {
            val index = cellIndex(sample.xInches, sample.yInches)
            if (index == null) {
                outside++
            } else {
                counts[index]++
                when (sample.isStrike) {
                    true -> strikes[index]++
                    false -> balls[index]++
                    null -> Unit
                }
            }
        }

        val cells = (0 until GRID * GRID).map { index ->
            ZoneCell(
                column = index % GRID,
                row = index / GRID,
                count = counts[index],
                strikeCount = strikes[index],
                ballCount = balls[index]
            )
        }

        val centroidX = samples.map { it.xInches }.average()
        val centroidY = samples.map { it.yInches }.average()
        val spread = sqrt(
            samples.sumOf { sample ->
                val dx = sample.xInches - centroidX
                val dy = sample.yInches - centroidY
                dx * dx + dy * dy
            } / samples.size
        )

        // 散佈 5 in 以內算很穩（80 分），每多 1 in 扣 4 分；25 in 以上視為完全失控
        val score = (100 - spread * 4.0).roundToInt().coerceIn(0, 100)
        return ControlReport(
            sampleCount = samples.size,
            outsideZoneCount = outside,
            cells = cells,
            centroidXInches = centroidX,
            centroidYInches = centroidY,
            spreadRadiusInches = spread,
            score = score,
            grade = gradeFor(score)
        )
    }

    fun gradeFor(score: Int): String = when {
        score >= 85 -> "A"
        score >= 70 -> "B"
        score >= 55 -> "C"
        else -> "D"
    }

    /**
     * 「平均落點偏向 右 2.1 in、高 1.4 in」。
     * 以好球帶中心為原點，x 正為右、y 正為高；兩軸都不到 0.5 in 時說「正中」。
     */
    fun describeOffset(centroidX: Double?, centroidY: Double?): String {
        if (centroidX == null || centroidY == null) return "—"
        val dx = centroidX - ZONE_CENTER_X_IN
        val dy = centroidY - ZONE_CENTER_Y_IN
        val parts = mutableListOf<String>()
        if (abs(dx) >= 0.5) {
            parts += String.format(Locale.US, "%s %.1f in", if (dx > 0) "右" else "左", abs(dx))
        }
        if (abs(dy) >= 0.5) {
            parts += String.format(Locale.US, "%s %.1f in", if (dy > 0) "高" else "低", abs(dy))
        }
        return if (parts.isEmpty()) "正中" else parts.joinToString("、")
    }
}

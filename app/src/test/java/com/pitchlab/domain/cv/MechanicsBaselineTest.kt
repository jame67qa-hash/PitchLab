package com.pitchlab.domain.cv

import com.pitchlab.domain.model.PitchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MechanicsBaselineTest {

    private fun session(
        score: Int?,
        separation: Double? = 28.0,
        elbow: Double? = 168.0,
        trunk: Double? = 19.0,
        height: Double? = 0.87
    ) = PitchSession(
        startedAtMillis = 1_700_000_000_000L,
        durationMillis = 5_000L,
        poseScore = score,
        separationDeg = separation,
        elbowDeg = elbow,
        trunkTiltDeg = trunk,
        releaseHeightRatio = height
    )

    @Test
    fun `median handles odd even and empty input`() {
        assertNull(MechanicsBaselineCalculator.median(emptyList()))
        assertEquals(28.0, MechanicsBaselineCalculator.median(listOf(30.0, 28.0, 20.0))!!, 1e-9)
        assertEquals(27.0, MechanicsBaselineCalculator.median(listOf(30.0, 28.0, 26.0, 20.0))!!, 1e-9)
        // 中位數對離群值不敏感：一筆 999 不該把基準拉到 300 以上
        assertEquals(28.0, MechanicsBaselineCalculator.median(listOf(28.0, 27.0, 999.0))!!, 1e-9)
    }

    @Test
    fun `baseline ignores sessions without pose score and uses median`() {
        val sessions = listOf(
            session(80, separation = 30.0),
            session(70, separation = 28.0),
            session(90, separation = 20.0),
            session(null, separation = 5.0) // 沒姿勢分數 -> 不進基準
        )
        val baseline = MechanicsBaselineCalculator.from(sessions)
        assertEquals(3, baseline.sampleCount)
        assertEquals(28.0, baseline.separationDeg!!, 1e-9)
        assertEquals(80.0, baseline.averageScore!!, 1e-9)
        assertFalse("3 球不足最小樣本", baseline.isUsable)
    }

    @Test
    fun `baseline becomes usable at five samples`() {
        val sessions = (1..5).map { session(75, separation = 26.0 + it) }
        val baseline = MechanicsBaselineCalculator.from(sessions)
        assertEquals(5, baseline.sampleCount)
        assertTrue(baseline.isUsable)
        assertEquals(29.0, baseline.separationDeg!!, 1e-9)
    }

    @Test
    fun `deviations skip missing metrics and flag only meaningful gaps`() {
        val baseline = MechanicsBaseline(
            sampleCount = 10,
            separationDeg = 28.0,
            elbowDeg = 168.0,
            trunkTiltDeg = 19.0,
            releaseHeightRatio = 0.87
        )
        val snapshot = MechanicsSnapshot(
            separationDeg = 22.0,     // -6 -> 顯著
            elbowDeg = 170.0,         // +2 -> 不顯著
            trunkTiltDeg = null,      // 缺值 -> 不比較
            releaseHeightRatio = 0.79 // -0.08 -> 顯著
        )
        val deltas = MechanicsBaselineCalculator.deviations(snapshot, baseline)
        assertEquals(3, deltas.size)
        assertTrue(deltas.first { it.label == "肩髖分離" }.significant)
        assertFalse(deltas.first { it.label == "前導手肘" }.significant)
        assertTrue(deltas.first { it.label == "放球高度" }.significant)
        assertEquals("−6°", deltas.first { it.label == "肩髖分離" }.deltaText())
    }

    @Test
    fun `delta text renders sign and decimals`() {
        val positive = BaselineDelta("前導手肘", 170.0, 166.0, 4.0, "°", false)
        assertEquals("+4°", positive.deltaText())
        assertEquals("+4.0°", positive.deltaText(1))
        val negativeHeight = BaselineDelta("放球高度", 0.80, 0.87, -0.07, "", true)
        assertEquals("−0.07", negativeHeight.deltaText(2))
    }
}

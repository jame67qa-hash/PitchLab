package com.pitchlab.domain.cv

import com.pitchlab.domain.model.PitchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTrendTest {

    private fun session(at: Long, height: Double?, mph: Double? = null) = PitchSession(
        startedAtMillis = at,
        durationMillis = 1L,
        releaseHeightFt = height,
        fastestMph = mph
    )

    @Test
    fun `fewer than three points is reported as not enough data`() {
        // 資料庫順序是新的在前
        val report = ReleaseTrend.from(listOf(session(2, 5.9), session(1, 6.0)))
        assertFalse(report.hasData)
        assertEquals("至少 3 球才畫得出趨勢", report.verdict)
        assertEquals(2, report.points.size)
    }

    @Test
    fun `points are reordered old to new`() {
        val report = ReleaseTrend.from(listOf(session(3, 5.7), session(2, 5.8), session(1, 5.9)))
        assertEquals(listOf(5.9, 5.8, 5.7), report.points.map { it.heightFt })
        assertEquals(listOf(1, 2, 3), report.points.map { it.index })
    }

    @Test
    fun `dropping release point gets a fatigue warning`() {
        val report = ReleaseTrend.from(listOf(session(3, 5.4), session(2, 5.8), session(1, 6.0)))
        assertTrue(report.slopePerPitch < -0.08)
        assertEquals("放球點逐步下墜，留意疲勞或掉臂", report.verdict)
        assertNotNull(report.deltaFt)
    }

    @Test
    fun `flat release point is called stable`() {
        val report = ReleaseTrend.from(listOf(session(3, 6.0), session(2, 6.01), session(1, 5.99)))
        assertTrue(report.verdict == "放球點大致穩定")
        assertEquals(6.0, report.averageHeightFt!!, 0.05)
    }

    @Test
    fun `height and speed dropping together raises fatigue flag`() {
        val report = ReleaseTrend.from(
            listOf(
                session(3, 5.4, 78.0),
                session(2, 5.7, 80.0),
                session(1, 6.0, 82.0)
            )
        )
        assertTrue(report.speedSlopePerPitch < -0.3)
        assertEquals("放球點與球速同步下滑，疲勞可能性高", report.verdict)
        assertEquals(80.0, report.averageSpeedMph!!, 0.01)
    }

    @Test
    fun `stable height with dropping speed is called out separately`() {
        val report = ReleaseTrend.from(
            listOf(
                session(3, 5.99, 78.0),
                session(2, 6.0, 80.0),
                session(1, 6.01, 82.0)
            )
        )
        assertEquals("放球點還穩，但球速在下滑", report.verdict)
    }

    @Test
    fun `sessions without release height are skipped`() {
        val report = ReleaseTrend.from(
            listOf(session(5, 5.8), session(4, null), session(3, 5.9), session(2, null), session(1, 6.0))
        )
        assertTrue(report.hasData)
        assertEquals(3, report.points.size)
        assertEquals(listOf(6.0, 5.9, 5.8), report.points.map { it.heightFt })
    }

    @Test
    fun `limit keeps only newest pitches`() {
        val sessions = (10 downTo 1).map { session(it.toLong(), 5.0 + it * 0.1) }
        val report = ReleaseTrend.from(sessions, limit = 5)
        assertEquals(5, report.points.size)
        // 最新的 5 球是 10..6，反轉後舊 -> 新為 6..10
        assertEquals(listOf(5.6, 5.7, 5.8, 5.9, 6.0), report.points.map { it.heightFt })
    }
}

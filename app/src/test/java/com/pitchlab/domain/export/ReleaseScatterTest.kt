package com.pitchlab.domain.export

import com.pitchlab.domain.model.PitchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseScatterTest {

    private fun session(height: Double?, distance: Double?, strike: Boolean? = true) = PitchSession(
        startedAtMillis = 1L,
        durationMillis = 1L,
        isStrike = strike,
        releaseHeightFt = height,
        releaseDistanceFt = distance
    )

    @Test
    fun `points require both values inside physical range`() {
        val sessions = listOf(
            session(5.8, 54.2),
            session(null, 54.0),      // 缺高度
            session(6.0, null),       // 缺距離
            session(0.5, 54.0),       // 高度不合理
            session(6.0, 20.0)        // 距離不合理
        )
        val points = ReleaseScatter.pointsOf(sessions)
        assertEquals(1, points.size)
        assertEquals(54.2, points.first().distanceFt, 1e-9)
        assertTrue(points.first().isStrike == true)
    }

    @Test
    fun `normalization maps view range to zero and one`() {
        assertEquals(0f, ReleaseScatter.normalizeX(ReleaseScatter.VIEW_MIN_DISTANCE_FT), 1e-6f)
        assertEquals(1f, ReleaseScatter.normalizeX(ReleaseScatter.VIEW_MAX_DISTANCE_FT), 1e-6f)
        assertEquals(0.5f, ReleaseScatter.normalizeX(51.0), 1e-6f)
        assertEquals(0f, ReleaseScatter.normalizeY(2.0), 1e-6f)
        assertEquals(1f, ReleaseScatter.normalizeY(9.0), 1e-6f)
    }

    @Test
    fun `values outside the view are clamped instead of drawn outside`() {
        assertEquals(0f, ReleaseScatter.normalizeX(10.0), 1e-6f)
        assertEquals(1f, ReleaseScatter.normalizeX(99.0), 1e-6f)
        assertEquals(0f, ReleaseScatter.normalizeY(-5.0), 1e-6f)
        assertEquals(1f, ReleaseScatter.normalizeY(20.0), 1e-6f)
    }
}

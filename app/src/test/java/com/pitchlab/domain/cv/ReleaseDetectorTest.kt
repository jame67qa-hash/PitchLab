package com.pitchlab.domain.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseDetectorTest {

    private fun feed(
        detector: ReleaseDetector,
        startMs: Long,
        stepMs: Long,
        deltas: List<Double>,
        visibility: Double = 0.9
    ): Pair<ReleaseDetector.Release?, Long> {
        var t = startMs
        var x = 0.2
        var release: ReleaseDetector.Release? = null
        for (delta in deltas) {
            t += stepMs
            x += delta
            release = detector.update(WristSample(t, x, 0.3, visibility)) ?: release
        }
        return release to t
    }

    @Test
    fun `slow arm movement never triggers release`() {
        val detector = ReleaseDetector()
        val quiet = List(20) { 0.004 }
        val (release, _) = feed(detector, 0L, 100L, quiet)
        assertNull(release)
    }

    @Test
    fun `whip produces exactly one release at the peak`() {
        val detector = ReleaseDetector()
        // 慢速抬手 -> 三次大幅甩腕 -> 收手
        val deltas = List(10) { 0.002 } + listOf(0.12, 0.16, 0.14) + List(8) { 0.001 }
        val (release, endMs) = feed(detector, 0L, 100L, deltas)
        assertNotNull(release)
        // 峰值應該落在甩腕區間內，而不是收手之後
        assertTrue("release at ${release!!.timestampMs}", release.timestampMs < endMs)
        assertTrue(release.acceleration >= 8.0)
    }

    @Test
    fun `cooldown suppresses a second peak within 900ms`() {
        val detector = ReleaseDetector()
        val deltas = List(6) { 0.001 } + listOf(0.14, 0.15) + List(3) { 0.001 } +
            listOf(0.14, 0.15) + List(4) { 0.001 }
        var t = 0L
        var x = 0.2
        var releaseCount = 0
        for (delta in deltas) {
            t += 100L
            x += delta
            if (detector.update(WristSample(t, x, 0.3, 0.9)) != null) releaseCount++
        }
        assertEquals(1, releaseCount)
    }

    @Test
    fun `low visibility samples are ignored`() {
        val detector = ReleaseDetector()
        val (release, _) = feed(detector, 0L, 100L, listOf(0.15, 0.15, 0.15), visibility = 0.2)
        assertNull(release)
    }

    @Test
    fun `reset clears internal state`() {
        val detector = ReleaseDetector()
        feed(detector, 0L, 100L, listOf(0.15, 0.15))
        detector.reset()
        assertEquals(0, detector.sampleCount)
    }
}

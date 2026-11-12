package com.pitchlab.domain.cv

import com.pitchlab.domain.model.CalibrationProfile
import com.pitchlab.domain.model.NormalizedPoint
import com.pitchlab.domain.model.PlateLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrikeZoneEvaluatorTest {

    private val evaluator = StrikeZoneEvaluator(TrajectoryReconstructor())

    private val profile = CalibrationProfile(
        corners = listOf(
            NormalizedPoint(0.3f, 0.72f),
            NormalizedPoint(0.5f, 0.72f),
            NormalizedPoint(0.5f, 0.8f),
            NormalizedPoint(0.3f, 0.8f)
        ),
        cameraHeightIn = 44.0
    )

    @Test
    fun `center of the zone at knee height is a strike`() {
        // y 向下為正：+10 in 偏移 -> 球高 34 in
        val result = evaluator.evaluate(PlateLocation(0.0, 10.0), profile)!!
        assertEquals(34.0, result.heightInches!!, 1e-9)
        assertTrue(result.isStrike)
        assertTrue(result.calibrated)
    }

    @Test
    fun `pitch outside the plate is a ball`() {
        val result = evaluator.evaluate(PlateLocation(11.0, 10.0), profile)!!
        assertFalse(result.isStrike)
    }

    @Test
    fun `pitch above the zone is a ball`() {
        // y = -6 -> 球高 50 in，超過 42 in 上緣
        val result = evaluator.evaluate(PlateLocation(0.0, -6.0), profile)!!
        assertEquals(50.0, result.heightInches!!, 1e-9)
        assertFalse(result.isStrike)
    }

    @Test
    fun `ball radius tolerance keeps edge pitches as strikes`() {
        // 8.5 + 1.47 = 9.97 in 仍算好球
        val edge = evaluator.evaluate(PlateLocation(9.9, 10.0), profile)!!
        assertTrue(edge.isStrike)
    }

    @Test
    fun `no plate location means no judgement`() {
        assertNull(evaluator.evaluate(null, profile))
    }

    @Test
    fun `uncalibrated profile still evaluates but flags calibrated false`() {
        val result = evaluator.evaluate(PlateLocation(0.0, 10.0), CalibrationProfile.NONE)
        assertNotNull(result)
        assertFalse(result!!.calibrated)
    }
}

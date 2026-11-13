package com.pitchlab.domain.cv

import com.pitchlab.domain.model.PitchSession
import com.pitchlab.domain.model.PitchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchTypeClassifierTest {

    private fun suggest(mph: Double?, hb: Double?, vb: Double?) =
        PitchTypeClassifier.suggest(mph, hb, vb)

    @Test
    fun `hard straight pitch is a fastball`() {
        val result = suggest(88.0, 3.0, 2.0)!!
        assertEquals(PitchType.FASTBALL, result.type)
        assertTrue(result.confident)
        assertTrue(result.reason.contains("88 mph"))
    }

    @Test
    fun `big drop at low speed is a curveball`() {
        val result = suggest(72.0, -6.0, -14.0)!!
        assertEquals(PitchType.CURVEBALL, result.type)
        assertTrue(result.confident)
    }

    @Test
    fun `large horizontal movement is a slider`() {
        val result = suggest(84.0, -12.0, -2.0)!!
        assertEquals(PitchType.SLIDER, result.type)
        assertTrue(result.confident)
    }

    @Test
    fun `slow with mild movement is a changeup`() {
        val result = suggest(70.0, 4.0, -3.0)!!
        assertEquals(PitchType.CHANGEUP, result.type)
        assertTrue(result.confident)
    }

    @Test
    fun `untypical mix falls back to other without confidence`() {
        val result = suggest(80.0, 0.0, -5.0)!!
        assertEquals(PitchType.OTHER, result.type)
        assertFalse("特徵不典型時不能自信", result.confident)
    }

    @Test
    fun `missing or impossible inputs are not guessed`() {
        assertNull(suggest(null, 3.0, 1.0))
        assertNull(suggest(88.0, null, 1.0))
        assertNull(suggest(88.0, 3.0, null))
        assertNull(suggest(150.0, 3.0, 1.0))
        assertNull(suggest(10.0, 3.0, 1.0))
    }

    @Test
    fun `session overload reads stored metrics`() {
        val session = PitchSession(
            startedAtMillis = 1L,
            durationMillis = 1L,
            fastestMph = 71.0,
            horizontalBreakInches = -5.0,
            verticalBreakInches = -13.0
        )
        assertEquals(PitchType.CURVEBALL, PitchTypeClassifier.suggest(session)!!.type)
        assertNull(PitchTypeClassifier.suggest(PitchSession(startedAtMillis = 1L, durationMillis = 1L)))
    }
}

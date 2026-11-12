package com.pitchlab.domain.cv

import com.pitchlab.domain.model.PitchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFingerprintTest {

    private val base = CameraFingerprint(heightIn = 44.0, pitchDeg = 3.0, speedScale = 1.0)

    @Test
    fun `identical setups match`() {
        assertTrue(base.matches(CameraFingerprint(44.0, 3.0, 1.0)))
    }

    @Test
    fun `tolerance boundaries are inclusive`() {
        assertTrue(base.matches(CameraFingerprint(46.0, 4.5, 1.10)))
        assertTrue(base.matches(CameraFingerprint(42.0, 1.5, 0.90)))
    }

    @Test
    fun `differences beyond tolerance break the match`() {
        assertFalse(base.matches(CameraFingerprint(46.5, 3.0, 1.0)))  // 高度差 2.5 in
        assertFalse(base.matches(CameraFingerprint(44.0, 4.6, 1.0)))  // 俯仰差 1.6°
        assertFalse(base.matches(CameraFingerprint(44.0, 3.0, 1.2)))  // 刻度差 20%
    }

    @Test
    fun `session fingerprint reads stored setup and defaults speed scale`() {
        val session = PitchSession(
            startedAtMillis = 1L,
            durationMillis = 1L,
            cameraHeightIn = 44.0,
            cameraPitchDeg = 3.0
        )
        val fingerprint = CameraFingerprint.of(session)!!
        assertEquals(44.0, fingerprint.heightIn, 1e-9)
        assertEquals(3.0, fingerprint.pitchDeg, 1e-9)
        assertEquals(1.0, fingerprint.speedScale, 1e-9)
    }

    @Test
    fun `legacy session without setup has no fingerprint`() {
        assertNull(CameraFingerprint.of(PitchSession(startedAtMillis = 1L, durationMillis = 1L)))
    }

    @Test
    fun `label shows height and signed pitch`() {
        assertEquals("機位 44 in · +3.0°", base.label())
        assertEquals("機位 44 in · -2.0°", CameraFingerprint(44.0, -2.0).label())
    }
}

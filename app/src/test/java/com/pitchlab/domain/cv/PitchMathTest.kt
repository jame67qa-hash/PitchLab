package com.pitchlab.domain.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 純數學單元測試：不依賴相機與 OpenCV native 庫，驗證核心換算公式。
 * 這些公式一旦算錯，UI 上的球速與位移會整體偏移，所以在 Phase 1 就鎖定。
 */
class PitchMathTest {

    private val calculator = PerspectiveTransformCalculator()

    @Test
    fun `60_5 feet in 0_4 seconds is about 103 mph`() {
        val mph = calculator.averageSpeedMph(
            distanceInches = PerspectiveTransformCalculator.PITCH_DISTANCE_IN,
            elapsedMillis = 400
        )
        // 726 in / 0.4 s = 1815 in/s = 103.1 mph（忽略空氣阻力）
        assertEquals(103.1, mph, 0.2)
    }

    @Test
    fun `17_6 inches per second equals one mph`() {
        assertEquals(1.0, calculator.inchesPerSecondToMph(17.6), 1e-9)
    }

    @Test
    fun `release speed estimate applies drag compensation`() {
        assertEquals(103.5, calculator.releaseSpeedEstimateMph(100.0), 0.001)
    }

    @Test
    fun `depth from apparent radius matches pinhole model`() {
        // f=900px, 真實半徑 1.47in, 像素半徑 6px -> 220.5 in
        val depth = calculator.depthFromApparentRadius(
            radiusPx = 6.0,
            focalPx = 900.0,
            realRadiusIn = PerspectiveTransformCalculator.BALL_RADIUS_IN
        )
        assertEquals(220.5, depth, 0.01)
    }

    @Test
    fun `depth is zero for invalid inputs`() {
        assertEquals(0.0, calculator.depthFromApparentRadius(0.0, 900.0), 1e-9)
        assertEquals(0.0, calculator.depthFromApparentRadius(6.0, 0.0), 1e-9)
    }

    @Test
    fun `least squares slope of constant velocity`() {
        val times = doubleArrayOf(0.0, 0.01, 0.02, 0.03, 0.04)
        val positions = doubleArrayOf(0.0, 60.0, 120.0, 180.0, 240.0)
        val slope = calculator.leastSquaresSlope(times, positions)
        assertEquals(6000.0, slope!!, 1e-6)
    }

    @Test
    fun `least squares slope needs at least two samples`() {
        assertNull(calculator.leastSquaresSlope(doubleArrayOf(0.0), doubleArrayOf(1.0)))
        assertNull(
            calculator.leastSquaresSlope(
                doubleArrayOf(0.0, 0.0, 0.0),
                doubleArrayOf(1.0, 2.0, 3.0)
            )
        )
    }

    @Test
    fun `strike zone boundaries map to plate dimensions`() {
        assertTrue(calculator.isInStrikeZone(0.0, 30.0))
        assertTrue(calculator.isInStrikeZone(-8.4, 19.0))
        assertFalse(calculator.isInStrikeZone(-9.0, 30.0))
        assertFalse(calculator.isInStrikeZone(0.0, 45.0))
        assertFalse(calculator.isInStrikeZone(0.0, 10.0))
    }

    @Test
    fun `speed from 3d velocity uses euclidean norm`() {
        val mph = calculator.speedMphFromVelocity(0.0, 0.0, -1760.0)
        assertEquals(100.0, mph, 1e-9)
    }

    @Test
    fun `project to plate extrapolates linear motion`() {
        val result = calculator.projectToPlate(
            positionIn = com.pitchlab.domain.model.Point3D(0.0, 0.0, 100.0),
            vxInPerSec = 100.0,
            vyInPerSec = 0.0,
            vzInPerSec = -1000.0,
            plateDepthIn = 0.0
        )
        // 100 in 距離、1000 in/s 接近 => 0.1s，橫向漂移 10 in
        assertEquals(0.1, result!!.third, 1e-9)
        assertEquals(10.0, result.first, 1e-9)
    }

    @Test
    fun `project to plate returns null when ball is moving away`() {
        val result = calculator.projectToPlate(
            positionIn = com.pitchlab.domain.model.Point3D(0.0, 0.0, 100.0),
            vxInPerSec = 0.0,
            vyInPerSec = 0.0,
            vzInPerSec = 50.0
        )
        assertNull(result)
    }
}

package com.pitchlab.domain.cv

import com.pitchlab.domain.pose.PoseFrame
import com.pitchlab.domain.pose.PoseIndices
import com.pitchlab.domain.pose.PosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PitchMechanicsEvaluatorTest {

    private val evaluator = PitchMechanicsEvaluator()

    private fun p(x: Double, y: Double, visibility: Double = 0.95) = PosePoint(x, y, visibility)

    private fun frameOf(vararg points: Pair<Int, PosePoint>) = PoseFrame(0L, points.toMap())

    /**
     * 一個「好出手」的合成姿勢：
     * 肩線相對髖線約 25°、軀幹前傾約 20°、投球臂伸直、手腕約在肩高（身高比 0.86）。
     */
    private fun goodReleaseFrame(vararg extra: Pair<Int, PosePoint>) = frameOf(
        PoseIndices.LEFT_SHOULDER to p(-0.0139, 0.3905),
        PoseIndices.RIGHT_SHOULDER to p(0.2031, 0.2895),
        PoseIndices.LEFT_HIP to p(-0.08, 0.60),
        PoseIndices.RIGHT_HIP to p(0.08, 0.60),
        PoseIndices.RIGHT_ELBOW to p(0.2731, 0.3495),
        PoseIndices.RIGHT_WRIST to p(0.3431, 0.4095),
        PoseIndices.RIGHT_ANKLE to p(0.06, 0.85),
        PoseIndices.LEFT_ANKLE to p(-0.06, 0.85),
        *extra
    )

    @Test
    fun `good release pose scores an A`() {
        val feedback = evaluator.evaluate(goodReleaseFrame())
        assertNotNull(feedback)
        assertEquals("A", feedback!!.grade)
        assertTrue(feedback.score >= 85)
        assertEquals(180.0, feedback.leadElbowDeg!!, 2.0)
        assertEquals(25.0, feedback.separationDeg!!, 1.5)
        assertEquals(20.0, feedback.trunkTiltDeg!!, 1.5)
    }

    @Test
    fun `shoulder hip separation is detected`() {
        // 右肩往上轉，肩線相對髖線形成明顯扭轉
        val twisted = goodReleaseFrame(
            PoseIndices.RIGHT_SHOULDER to p(0.10, 0.30)
        )
        val feedback = evaluator.evaluate(twisted)
        assertNotNull(feedback)
        val separation = feedback!!.separationDeg!!
        assertTrue("separation=$separation", separation in 15.0..70.0)
        assertTrue(feedback.score in 0..100)
    }

    @Test
    fun `bent elbow reads about ninety degrees`() {
        val bent = goodReleaseFrame(
            PoseIndices.RIGHT_ELBOW to p(0.2831, 0.3095),
            PoseIndices.RIGHT_WRIST to p(0.3031, 0.2295)
        )
        val feedback = evaluator.evaluate(bent)
        assertNotNull(feedback)
        assertEquals(90.0, feedback!!.leadElbowDeg!!, 1.0)
        assertTrue(feedback.score < 85)
    }

    @Test
    fun `trunk tilt measures lean from vertical`() {
        val leaning = goodReleaseFrame(
            PoseIndices.LEFT_SHOULDER to p(0.05, 0.40),
            PoseIndices.RIGHT_SHOULDER to p(0.15, 0.40)
        )
        val feedback = evaluator.evaluate(leaning)
        assertNotNull(feedback)
        assertEquals(26.57, feedback!!.trunkTiltDeg!!, 0.5)
    }

    @Test
    fun `invisible shoulders yield no feedback`() {
        val hidden = frameOf(
            PoseIndices.LEFT_SHOULDER to p(-0.10, 0.40, 0.1),
            PoseIndices.RIGHT_SHOULDER to p(0.10, 0.40, 0.1),
            PoseIndices.LEFT_HIP to p(-0.08, 0.60),
            PoseIndices.RIGHT_HIP to p(0.08, 0.60),
            PoseIndices.RIGHT_WRIST to p(0.26, 0.49)
        )
        assertNull(evaluator.evaluate(hidden))
    }

    @Test
    fun `angle helper matches geometry`() {
        val vertex = p(0.0, 0.0)
        val a = p(1.0, 0.0)
        val b = p(0.0, 1.0)
        assertEquals(90.0, evaluator.angleBetweenPoints(vertex, a, b), 1e-6)
        assertEquals(1.4142135623730951, evaluator.distance(a, b), 1e-9)
        assertTrue(abs(evaluator.distance(p(0.0, 0.0), p(3.0, 4.0)) - 5.0) < 1e-6)
    }
}

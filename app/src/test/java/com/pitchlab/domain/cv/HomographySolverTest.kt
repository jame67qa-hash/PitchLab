package com.pitchlab.domain.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四點單應解算的數值驗證。
 * 手法：先給一個已知矩陣 h_true，用它把四個原始點變換成「觀測到的」點，
 * 再用 solver 反解，最後驗證第五個點（未參與解算）也被正確映射。
 */
class HomographySolverTest {

    private val solver = HomographySolver()

    private val truth = doubleArrayOf(
        1.06, 0.04, 12.0,
        -0.03, 1.02, -8.0,
        0.0006, -0.0004, 1.0
    )

    private val unitSquare = listOf(
        HomographySolver.XY(0.0, 0.0),
        HomographySolver.XY(1.0, 0.0),
        HomographySolver.XY(1.0, 1.0),
        HomographySolver.XY(0.0, 1.0)
    )

    private fun transform(h: DoubleArray, x: Double, y: Double): HomographySolver.XY {
        val w = h[6] * x + h[7] * y + h[8]
        return HomographySolver.XY(
            (h[0] * x + h[1] * y + h[2]) / w,
            (h[3] * x + h[4] * y + h[5]) / w
        )
    }

    @Test
    fun `recovers projective transform from four correspondences`() {
        val dst = unitSquare.map { transform(truth, it.x, it.y) }
        val h = solver.solve(unitSquare, dst)
        assertNotNull(h)

        val probe = HomographySolver.XY(0.37, 0.62)
        val expected = transform(truth, probe.x, probe.y)
        val actual = solver.apply(h!!, probe.x, probe.y)!!
        assertEquals(expected.x, actual.x, 1e-6)
        assertEquals(expected.y, actual.y, 1e-6)
    }

    @Test
    fun `inverse matrix maps back to source`() {
        val dst = unitSquare.map { transform(truth, it.x, it.y) }
        val h = solver.solve(unitSquare, dst)!!
        val inverse = solver.invert(h)
        assertNotNull(inverse)

        val point = HomographySolver.XY(0.2, 0.85)
        val forward = solver.apply(h, point.x, point.y)!!
        val back = solver.apply(inverse!!, forward.x, forward.y)!!
        assertEquals(point.x, back.x, 1e-6)
        assertEquals(point.y, back.y, 1e-6)
    }

    @Test
    fun `collinear points are rejected`() {
        val line = listOf(
            HomographySolver.XY(0.0, 0.0),
            HomographySolver.XY(0.2, 0.2),
            HomographySolver.XY(0.5, 0.5),
            HomographySolver.XY(0.8, 0.8)
        )
        val dst = listOf(
            HomographySolver.XY(-8.5, -8.5),
            HomographySolver.XY(8.5, -8.5),
            HomographySolver.XY(8.5, 0.0),
            HomographySolver.XY(-8.5, 0.0)
        )
        assertNull(solver.solve(line, dst))
    }

    @Test
    fun `reprojection error is zero for an exact mapping`() {
        val dst = unitSquare.map { transform(truth, it.x, it.y) }
        val h = solver.solve(unitSquare, dst)!!
        val error = solver.averageReprojectionError(unitSquare, dst, h)
        assertTrue("誤差應接近 0，實際 $error", error < 1e-9)
    }

    @Test
    fun `homography maps normalized tap to plate inches`() {
        // 模擬：本壘板在畫面中偏左下，寬 0.2（歸一化），前緣 y=0.75
        val corners = listOf(
            HomographySolver.XY(0.30, 0.68),
            HomographySolver.XY(0.50, 0.68),
            HomographySolver.XY(0.52, 0.76),
            HomographySolver.XY(0.28, 0.76)
        )
        val field = listOf(
            HomographySolver.XY(-8.5, -8.5),
            HomographySolver.XY(8.5, -8.5),
            HomographySolver.XY(8.5, 0.0),
            HomographySolver.XY(-8.5, 0.0)
        )
        val h = solver.solve(corners, field)!!
        val center = solver.apply(h, 0.40, 0.72)!!
        // 本壘板中心應落在 x≈0、y≈-4.25 左右
        assertEquals(0.0, center.x, 1.5)
        assertEquals(-4.25, center.y, 1.5)
    }
}

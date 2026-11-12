package com.pitchlab.domain.cv

import com.pitchlab.domain.model.Point3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sin

class TrajectoryReconstructorTest {

    private val reconstructor = TrajectoryReconstructor()

    @Test
    fun `horizontal camera keeps camera height on optical axis`() {
        val setup = CameraSetup(heightIn = 44.0, pitchDeg = 0.0)
        val world = reconstructor.reconstruct(Point3D(0.0, 0.0, 600.0), setup)
        assertEquals(0.0, world.xInches, 1e-9)
        assertEquals(44.0, world.yInches, 1e-9)
        assertEquals(600.0, world.zInches, 1e-9)
        assertEquals(50.0, reconstructor.releaseDistanceFeet(Point3D(0.0, 0.0, 600.0), setup)!!, 1e-9)
    }

    @Test
    fun `pitch up raises distant points on the optical axis`() {
        val setup = CameraSetup(heightIn = 44.0, pitchDeg = 5.0)
        val point = Point3D(0.0, 0.0, 600.0)
        val world = reconstructor.reconstruct(point, setup)
        assertEquals(44.0 + 600.0 * sin(Math.toRadians(5.0)), world.yInches, 1e-6)
        assertEquals(600.0 * Math.cos(Math.toRadians(5.0)), world.zInches, 1e-6)
    }

    @Test
    fun `point below the optical axis lands lower`() {
        val setup = CameraSetup(heightIn = 44.0, pitchDeg = 0.0)
        val world = reconstructor.reconstruct(Point3D(0.0, 12.0, 600.0), setup)
        assertEquals(32.0, world.yInches, 1e-9) // 相機 y 向下為正 -> 高度 = 44 − 12
    }

    @Test
    fun `plate height applies cosine correction`() {
        val horizontal = CameraSetup(heightIn = 44.0, pitchDeg = 0.0)
        val tilted = CameraSetup(heightIn = 44.0, pitchDeg = 10.0)
        val y = 10.0
        assertEquals(34.0, reconstructor.plateHeightInches(y, horizontal), 1e-9)
        assertEquals(44.0 - 10.0 * Math.cos(Math.toRadians(10.0)), reconstructor.plateHeightInches(y, tilted), 1e-6)
    }

    @Test
    fun `implausible release values are rejected`() {
        val setup = CameraSetup(heightIn = 44.0, pitchDeg = 0.0)
        assertNull(reconstructor.releaseHeightFeet(Point3D(0.0, -600.0, 600.0), setup))
        assertNull(reconstructor.releaseHeightFeet(null, setup))
        assertNull(reconstructor.releaseDistanceFeet(Point3D(0.0, 0.0, 100.0), setup))
    }

    @Test
    fun `release height and distance in feet for a typical pitch`() {
        val setup = CameraSetup(heightIn = 44.0, pitchDeg = 3.0)
        // 出手：離壘 55 ft、球離光軸 6 in（相機 y 向下）
        val point = Point3D(0.0, 6.0, 660.0)
        val height = reconstructor.releaseHeightFeet(point, setup)!!
        val distance = reconstructor.releaseDistanceFeet(point, setup)!!
        assertEquals((44.0 - 6.0 * Math.cos(Math.toRadians(3.0)) + 660.0 * sin(Math.toRadians(3.0))) / 12.0, height, 1e-6)
        assertEquals((6.0 * sin(Math.toRadians(3.0)) + 660.0 * Math.cos(Math.toRadians(3.0))) / 12.0, distance, 1e-6)
    }
}

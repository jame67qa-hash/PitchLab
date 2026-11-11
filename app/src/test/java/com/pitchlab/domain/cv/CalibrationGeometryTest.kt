package com.pitchlab.domain.cv

import com.pitchlab.domain.model.CalibrationProfile
import com.pitchlab.domain.model.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationGeometryTest {

    private val geometry = CalibrationGeometry()

    /** 本壘板：x 0.30~0.50（寬 0.2 歸一化 = 17 in），前緣 y = 0.80 */
    private val corners = listOf(
        NormalizedPoint(0.30f, 0.72f),
        NormalizedPoint(0.50f, 0.72f),
        NormalizedPoint(0.50f, 0.80f),
        NormalizedPoint(0.30f, 0.80f)
    )

    private val profile = CalibrationProfile(corners = corners)

    @Test
    fun `plate width is measured from both edges`() {
        assertEquals(0.2, geometry.plateWidthNormalized(corners), 1e-6)
        assertEquals(0.4, geometry.plateCenterX(corners), 1e-6)
        assertEquals(0.80, geometry.plateFrontY(corners), 1e-6)
    }

    @Test
    fun `normalized y per inch depends on aspect ratio`() {
        // 1 in (y) = 0.2 * (16/9) / 17
        val aspect = 16f / 9f
        val expected = 0.2 * (16.0 / 9.0) / 17.0
        // 允許 Float aspect 的精度誤差（16f/9f 不是精確有理數）
        assertEquals(expected, geometry.normalizedYPerInch(corners, aspect), 1e-7)
    }

    @Test
    fun `strike zone rect spans 17 inches wide and 23 inches tall`() {
        val aspect = 16f / 9f
        // 這個案例把本壘板放得夠低，讓 42 in 的上緣仍在畫面內，避免 coerceIn 裁切
        val lowPlate = CalibrationProfile(
            corners = listOf(
                NormalizedPoint(0.30f, 0.87f),
                NormalizedPoint(0.50f, 0.87f),
                NormalizedPoint(0.50f, 0.95f),
                NormalizedPoint(0.30f, 0.95f)
            )
        )
        val rect = geometry.strikeZoneRect(lowPlate, aspect)
        assertNotNull(rect)
        assertEquals(0.2f, rect!!.width, 1e-5f)
        val expectedHeight = (23.0 * 0.2 * (16.0 / 9.0) / 17.0).toFloat()
        assertEquals(expectedHeight, rect.height, 2e-4f)
        // 底線應高於本壘板前緣（y 更小）
        assertTrue(rect.bottom < 0.95f)
        assertTrue(rect.top > 0f)
    }

    @Test
    fun `strike zone top is clamped when it would leave the frame`() {
        val rect = geometry.strikeZoneRect(profile, 16f / 9f)!!
        // 42 in 上緣超出畫面上緣 -> top 被夾到 0
        assertEquals(0f, rect.top, 1e-6f)
        assertTrue(rect.bottom < 0.80f)
    }

    @Test
    fun `strike zone is null without four corners`() {
        assertNull(geometry.strikeZoneRect(CalibrationProfile.NONE, 16f / 9f))
        assertNull(geometry.strikeZoneRect(profile, 0f))
    }

    @Test
    fun `pixels per inch scales with image width`() {
        assertEquals(0.2 * 1280.0 / 17.0, geometry.pixelsPerInch(corners, 1280), 1e-6)
        assertEquals(0.0, geometry.pixelsPerInch(corners, 0), 1e-9)
    }
}

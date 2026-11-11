package com.pitchlab.domain.feedback

import org.junit.Assert.assertEquals
import org.junit.Test

class PitchAnnouncementTest {

    @Test
    fun `strike with calibrated speed reads speed only`() {
        assertEquals("好球，72 英里", PitchAnnouncement.text(true, 72.4, calibrated = true))
    }

    @Test
    fun `uncalibrated speed is announced as estimate`() {
        assertEquals("壞球，預估 68 英里", PitchAnnouncement.text(false, 67.6, calibrated = false))
    }

    @Test
    fun `speed rounds to nearest mph`() {
        assertEquals("好球，73 英里", PitchAnnouncement.text(true, 72.6, calibrated = true))
    }

    @Test
    fun `missing or zero speed keeps the verdict only`() {
        assertEquals("未判定", PitchAnnouncement.text(null, null))
        assertEquals("好球", PitchAnnouncement.text(true, 0.0))
        assertEquals("壞球", PitchAnnouncement.text(false, -1.0))
    }
}

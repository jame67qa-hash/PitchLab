package com.pitchlab.domain.export

import com.pitchlab.domain.model.PitchSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCsvFormatterTest {

    private fun session(
        id: Long = 1L,
        isStrike: Boolean? = true,
        poseScore: Int? = 80,
        videoPath: String? = "/data/user/0/com.pitchlab/files/Movies/pitches/A.mp4",
        clipPath: String? = null
    ) = PitchSession(
        id = id,
        startedAtMillis = 1_700_000_000_000L,
        durationMillis = 10_500L,
        fastestMph = 72.4,
        averageMph = 70.1,
        horizontalBreakInches = -3.25,
        verticalBreakInches = 12.0,
        plateXInches = 2.5,
        plateYInches = 31.0,
        isStrike = isStrike,
        calibrated = true,
        videoPath = videoPath,
        detectedFps = 60,
        trackedFrames = 24,
        poseScore = poseScore,
        separationDeg = 28.4,
        elbowDeg = 168.2,
        trunkTiltDeg = 19.0,
        releaseHeightRatio = 0.87,
        releaseOffsetMs = 1_200L,
        clipPath = clipPath
    )

    @Test
    fun `csv starts with bom and has every row on crlf`() {
        val csv = SessionCsvFormatter.toCsv(listOf(session()))
        assertTrue(csv.startsWith(SessionCsvFormatter.BOM))
        assertTrue(csv.contains("\r\n"))
        // 表頭 + 1 筆資料
        val rows = csv.removePrefix(SessionCsvFormatter.BOM).trimEnd('\r', '\n').split("\r\n")
        assertEquals(2, rows.size)
        assertEquals(SessionCsvFormatter.HEADER.size, rows[0].split(",").size)
    }

    @Test
    fun `fields with commas quotes and newlines are escaped`() {
        assertEquals("plain", SessionCsvFormatter.escape("plain"))
        assertEquals("\"a,b\"", SessionCsvFormatter.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SessionCsvFormatter.escape("say \"hi\""))
        assertEquals("\"line1\nline2\"", SessionCsvFormatter.escape("line1\nline2"))
    }

    @Test
    fun `null metrics become empty columns and strike maps to yes no`() {
        val unknown = session(isStrike = null, poseScore = null).copy(
            fastestMph = null,
            averageMph = null,
            horizontalBreakInches = null,
            verticalBreakInches = null,
            plateXInches = null,
            plateYInches = null,
            separationDeg = null,
            elbowDeg = null,
            trunkTiltDeg = null,
            releaseHeightRatio = null,
            releaseOffsetMs = null,
            clipPath = null
        )
        val row = SessionCsvFormatter.rowOf(unknown)
        assertEquals("", row[SessionCsvFormatter.HEADER.indexOf("strike")])
        assertEquals("", row[SessionCsvFormatter.HEADER.indexOf("pose_score")])
        assertEquals("", row[SessionCsvFormatter.HEADER.indexOf("fastest_mph")])
        assertEquals("", row[SessionCsvFormatter.HEADER.indexOf("clip_file")])

        val ball = session(isStrike = false, poseScore = null)
        assertEquals("NO", SessionCsvFormatter.rowOf(ball)[SessionCsvFormatter.HEADER.indexOf("strike")])
        assertEquals("YES", SessionCsvFormatter.rowOf(session())[SessionCsvFormatter.HEADER.indexOf("calibrated")])
    }

    @Test
    fun `file names are extracted from absolute paths`() {
        val withClip = session(clipPath = "/storage/emulated/0/Android/data/com.pitchlab/files/Movies/pitches/A_pitch.mp4")
        val row = SessionCsvFormatter.rowOf(withClip)
        assertEquals("A.mp4", row[SessionCsvFormatter.HEADER.indexOf("video_file")])
        assertEquals("A_pitch.mp4", row[SessionCsvFormatter.HEADER.indexOf("clip_file")])
    }
}

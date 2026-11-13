package com.pitchlab.domain.export

import com.pitchlab.domain.model.PitchSession
import com.pitchlab.domain.model.PitchType
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchReportSummaryTest {

    private fun session(
        mph: Double? = 85.0,
        strike: Boolean? = true,
        score: Int? = 80,
        type: PitchType? = null,
        height: Double? = null,
        distance: Double? = null
    ) = PitchSession(
        startedAtMillis = 1L,
        durationMillis = 1L,
        fastestMph = mph,
        isStrike = strike,
        poseScore = score,
        pitchType = type,
        releaseHeightFt = height,
        releaseDistanceFt = distance
    )

    @Test
    fun `summary counts strikes balls and unknown`() {
        val lines = PitchReportSummary.build(
            listOf(session(), session(), session(strike = false), session(strike = null)),
            ExportOptions()
        )
        assertTrue(lines.any { it.contains("球數：4（好球 2 / 壞球 1 / 未判定 1）") })
    }

    @Test
    fun `speeds and scores are formatted with one decimal`() {
        val lines = PitchReportSummary.build(
            listOf(session(mph = 80.0, score = 70), session(mph = 90.0, score = 80)),
            ExportOptions()
        )
        assertTrue(lines.any { it == "球速：最快 90.0 mph、平均 85.0 mph" })
        assertTrue(lines.any { it == "姿勢平均分：75（A≥85、B≥70、C≥55）" })
    }

    @Test
    fun `types and release data are grouped`() {
        val lines = PitchReportSummary.build(
            listOf(
                session(type = PitchType.FASTBALL, height = 5.8, distance = 54.0),
                session(type = PitchType.FASTBALL, height = 6.0, distance = 53.0),
                session(type = PitchType.SLIDER)
            ),
            ExportOptions()
        )
        assertTrue(lines.any { it == "球種：速球 2 球、滑球 1 球" })
        assertTrue(lines.any { it.startsWith("3D 放球：平均高度 5.9 ft、平均距壘 53.5 ft（2 球）") })
    }

    @Test
    fun `empty data is stated instead of showing zero`() {
        val lines = PitchReportSummary.build(listOf(session(mph = null, score = null)), ExportOptions())
        assertTrue(lines.any { it == "球速：無有效資料" })
        assertTrue(lines.any { it == "姿勢分數：此批沒有偵測到投球動作" })
        assertTrue(lines.any { it == "球種：全部未標記" })
        assertTrue(lines.any { it.startsWith("3D 放球：無資料") })
        assertTrue(lines.any { it.contains("球速與位移由單一鏡頭估算") })
        assertTrue(lines.any { it.contains("姿勢分數是 2D 投影角度") })
    }
}

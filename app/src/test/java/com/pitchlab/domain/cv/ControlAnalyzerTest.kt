package com.pitchlab.domain.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAnalyzerTest {

    @Test
    fun `cell index maps corners and center`() {
        assertEquals(0, ControlAnalyzer.cellIndex(-8.5, 42.0))   // 左上（高）
        assertEquals(2, ControlAnalyzer.cellIndex(8.5, 42.0))    // 右上
        assertEquals(6, ControlAnalyzer.cellIndex(-8.5, 19.0))   // 左下
        assertEquals(8, ControlAnalyzer.cellIndex(8.4, 19.1))    // 右下
        assertEquals(4, ControlAnalyzer.cellIndex(0.0, 30.5))    // 正中
        assertNull(ControlAnalyzer.cellIndex(9.0, 30.5))         // 框外（右）
        assertNull(ControlAnalyzer.cellIndex(0.0, 45.0))         // 框外（高）
        assertNull(ControlAnalyzer.cellIndex(0.0, 18.0))         // 框外（低）
    }

    @Test
    fun `empty input yields empty report`() {
        val report = ControlAnalyzer.analyze(emptyList())
        assertEquals(ControlReport.EMPTY, report)
        assertNull(report.score)
        assertTrue(report.cells.isEmpty())
    }

    @Test
    fun `cells count only in-zone samples and outside are tallied separately`() {
        val samples = listOf(
            PlateSample(0.0, 30.5, true),
            PlateSample(-6.0, 38.0, false),
            PlateSample(6.0, 22.0, true),
            PlateSample(12.0, 30.0, null) // 框外
        )
        val report = ControlAnalyzer.analyze(samples)
        assertEquals(4, report.sampleCount)
        assertEquals(1, report.outsideZoneCount)
        assertEquals(9, report.cells.size)
        assertEquals(3, report.cells.sumOf { it.count })
        assertEquals(1, report.cells[4].count)     // 正中
        assertEquals(1, report.cells[0].count)     // 左上、壞球
        assertEquals(1, report.cells[0].ballCount)
        assertEquals(1, report.cells[8].count)     // 右下、好球
        assertEquals(1, report.cells[8].strikeCount)
    }

    @Test
    fun `symmetric samples give centered centroid and known spread`() {
        val samples = listOf(
            PlateSample(-3.0, 30.5, true),
            PlateSample(3.0, 30.5, true)
        )
        val report = ControlAnalyzer.analyze(samples)
        assertEquals(0.0, report.centroidXInches!!, 1e-9)
        assertEquals(30.5, report.centroidYInches!!, 1e-9)
        assertEquals(3.0, report.spreadRadiusInches!!, 1e-9)
        assertEquals(88, report.score)
        assertEquals("A", report.grade)
    }

    @Test
    fun `wide spread lowers score and grade`() {
        val samples = listOf(
            PlateSample(-15.0, 20.0, false),
            PlateSample(15.0, 41.0, false)
        )
        val report = ControlAnalyzer.analyze(samples)
        // 兩球都距平均落點 18.25 in -> 100 - 73 = 27 -> D
        assertEquals(27, report.score)
        assertEquals("D", report.grade)
        assertEquals(2, report.outsideZoneCount)
    }

    @Test
    fun `offset description names direction and omits tiny offsets`() {
        assertEquals("右 2.0 in、高 1.5 in", ControlAnalyzer.describeOffset(2.0, 32.0))
        assertEquals("左 4.0 in、低 3.5 in", ControlAnalyzer.describeOffset(-4.0, 27.0))
        assertEquals("正中", ControlAnalyzer.describeOffset(0.2, 30.7))
        assertEquals("—", ControlAnalyzer.describeOffset(null, null))
    }
}

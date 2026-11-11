package com.pitchlab.domain.export

import com.pitchlab.domain.model.PitchSession
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportSelectorTest {

    private fun session(id: Long, daysAgo: Long, isStrike: Boolean?) = PitchSession(
        id = id,
        startedAtMillis = NOW - daysAgo * 86_400_000L,
        durationMillis = 5_000L,
        isStrike = isStrike
    )

    private val NOW = 1_800_000_000_000L

    private val sessions = listOf(
        session(1, 0, true),
        session(2, 1, false),
        session(3, 6, true),
        session(4, 20, null),
        session(5, 45, true)
    )

    @Test
    fun `default options keep everything`() {
        assertEquals(5, ExportSelector.select(sessions, ExportOptions(), NOW).size)
    }

    @Test
    fun `strikes only drops balls and unknown`() {
        val selected = ExportSelector.select(sessions, ExportOptions(strikesOnly = true), NOW)
        assertEquals(listOf(1L, 3L, 5L), selected.map { it.id })
    }

    @Test
    fun `days back filters older sessions`() {
        // 第 3 筆是 6 天前，仍在 7 天視窗內；只有 20 / 45 天前的會被排除
        assertEquals(listOf(1L, 2L, 3L), ExportSelector.select(sessions, ExportOptions(daysBack = 7), NOW).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), ExportSelector.select(sessions, ExportOptions(daysBack = 30), NOW).map { it.id })
    }

    @Test
    fun `strikes and window combine`() {
        val selected = ExportSelector.select(
            sessions,
            ExportOptions(strikesOnly = true, daysBack = 30),
            NOW
        )
        assertEquals(listOf(1L, 3L), selected.map { it.id })
    }

    @Test
    fun `describe summarizes the scope in chinese`() {
        assertEquals("全部紀錄、含影片片段", ExportSelector.describe(ExportOptions()))
        assertEquals("最近 7 天、只含好球、不含影片", ExportSelector.describe(ExportOptions(strikesOnly = true, daysBack = 7, maxMediaBytes = 0)))
    }
}

package com.pitchlab.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PitcherTest {

    @Test
    fun `blank names are rejected`() {
        assertEquals("請輸入投手名稱", Pitcher.validate("   "))
    }

    @Test
    fun `overlong names are rejected`() {
        assertEquals("名稱最多 16 字", Pitcher.validate("一二三四五六七八九十一二三四五六七"))
    }

    @Test
    fun `normal names pass`() {
        assertNull(Pitcher.validate(" 王小明 "))
    }
}

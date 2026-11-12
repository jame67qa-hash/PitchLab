package com.pitchlab.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchTypeTest {

    @Test
    fun `codes round trip for every type`() {
        PitchType.entries.forEach { type ->
            assertEquals(type, PitchType.fromCode(type.code))
        }
    }

    @Test
    fun `unknown or missing codes are not guessed`() {
        assertNull(PitchType.fromCode(null))
        assertNull(PitchType.fromCode(""))
        assertNull(PitchType.fromCode("knuckleball"))
    }

    @Test
    fun `code matching ignores case`() {
        assertEquals(PitchType.FASTBALL, PitchType.fromCode("FastBall"))
    }

    @Test
    fun `every type has a distinct code and a label`() {
        assertEquals(PitchType.entries.size, PitchType.entries.map { it.code }.distinct().size)
        PitchType.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }
}

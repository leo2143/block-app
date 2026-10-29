package com.leo.blockapp.adapters.input.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinutesChipsTest {

    @Test
    fun `limit presets match the spec`() {
        assertEquals(listOf(5, 10, 15, 30, 60, 90), MinutesChips.LIMIT)
    }

    @Test
    fun `cooldown presets start at the minimum of 15`() {
        assertEquals(listOf(15, 30, 60, 90), MinutesChips.COOLDOWN)
    }

    @Test
    fun `a preset value is not custom`() {
        assertFalse(MinutesChips.isCustom(MinutesInput.of(10), MinutesChips.LIMIT))
    }

    @Test
    fun `a value outside the presets is custom`() {
        assertTrue(MinutesChips.isCustom(MinutesInput.of(45), MinutesChips.LIMIT))
    }

    @Test
    fun `an unparsable value is custom`() {
        assertTrue(MinutesChips.isCustom(MinutesInput.parse("abc"), MinutesChips.LIMIT))
        assertTrue(MinutesChips.isCustom(MinutesInput.parse(""), MinutesChips.COOLDOWN))
    }

    @Test
    fun `a value valid for limit but not for cooldown presets is custom there`() {
        assertTrue(MinutesChips.isCustom(MinutesInput.of(5), MinutesChips.COOLDOWN))
    }
}

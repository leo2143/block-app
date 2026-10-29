package com.leo.blockapp.adapters.input.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MinutesInputTest {

    @Test
    fun `digits parse to a value`() {
        assertEquals(MinutesField(text = "2000", value = 2000, error = null), MinutesInput.parse("2000"))
    }

    @Test
    fun `surrounding whitespace is trimmed before parsing and the text is kept as typed`() {
        val field = MinutesInput.parse("  15 ")

        assertEquals(15, field.value)
        assertNull(field.error)
        assertEquals("  15 ", field.text)
    }

    @Test
    fun `blank text is BLANK`() {
        assertEquals(MinutesError.BLANK, MinutesInput.parse("").error)
        assertEquals(MinutesError.BLANK, MinutesInput.parse("   ").error)
        assertNull(MinutesInput.parse("").value)
    }

    @Test
    fun `non digits are NOT_A_NUMBER`() {
        assertEquals(MinutesError.NOT_A_NUMBER, MinutesInput.parse("abc").error)
        assertEquals(MinutesError.NOT_A_NUMBER, MinutesInput.parse("-5").error)
        assertEquals(MinutesError.NOT_A_NUMBER, MinutesInput.parse("1.5").error)
        assertEquals(MinutesError.NOT_A_NUMBER, MinutesInput.parse("1 5").error)
        assertEquals(MinutesError.NOT_A_NUMBER, MinutesInput.parse("١٢").error)
    }

    @Test
    fun `digits beyond Int are OVERFLOW`() {
        val field = MinutesInput.parse("99999999999")

        assertEquals(MinutesError.OVERFLOW, field.error)
        assertNull(field.value)
    }

    @Test
    fun `Int max still parses`() {
        assertEquals(Int.MAX_VALUE, MinutesInput.parse(Int.MAX_VALUE.toString()).value)
    }

    @Test
    fun `a value builds a field with its text`() {
        assertEquals(MinutesField(text = "60", value = 60, error = null), MinutesInput.of(60))
    }
}

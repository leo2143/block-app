package com.leo.blockapp.adapters.input.ui.blocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestedBlockNameTest {

    @Test
    fun `no apps gives an empty suggestion`() {
        assertEquals("", SuggestedBlockName.from(emptyList()))
    }

    @Test
    fun `one app uses its label`() {
        assertEquals("Instagram", SuggestedBlockName.from(listOf("Instagram")))
    }

    @Test
    fun `two apps are joined with y`() {
        assertEquals("Instagram y X", SuggestedBlockName.from(listOf("Instagram", "X")))
    }

    @Test
    fun `more than two apps summarize the rest`() {
        assertEquals("Instagram, X y 1 más", SuggestedBlockName.from(listOf("Instagram", "X", "TikTok")))
        assertEquals("Instagram, X y 3 más", SuggestedBlockName.from(listOf("Instagram", "X", "a", "b", "c")))
    }

    @Test
    fun `selection order decides which labels lead`() {
        assertEquals("TikTok y Instagram", SuggestedBlockName.from(listOf("TikTok", "Instagram")))
    }

    @Test
    fun `a suggestion of exactly 40 characters is kept`() {
        val label = "a".repeat(40)

        assertEquals(label, SuggestedBlockName.from(listOf(label)))
    }

    @Test
    fun `a longer suggestion is truncated to 40 characters ending with an ellipsis`() {
        val suggestion = SuggestedBlockName.from(listOf("a".repeat(30), "b".repeat(30)))

        assertEquals(40, suggestion.length)
        assertTrue(suggestion.endsWith("…"))
        assertEquals("a".repeat(30) + " y " + "b".repeat(6) + "…", suggestion)
    }

    @Test
    fun `truncation never leaves half an emoji before the ellipsis`() {
        val suggestion = SuggestedBlockName.from(listOf("a".repeat(38) + "😀" + "b".repeat(5)))

        assertEquals("a".repeat(38) + "…", suggestion)
        assertTrue(suggestion.length <= 40)
    }

    @Test
    fun `truncation does not leave whitespace before the ellipsis`() {
        val suggestion = SuggestedBlockName.from(listOf("a".repeat(38) + " " + "b".repeat(5)))

        assertEquals("a".repeat(38) + "…", suggestion)
    }
}

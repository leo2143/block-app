package com.leo.blockapp.adapters.input.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockAppRoutesTest {

    @Test
    fun `start destination is the usage screen`() {
        assertEquals("usage", BlockAppRoutes.USAGE)
    }

    @Test
    fun `new editor route carries no block id`() {
        assertEquals("blocks/editor", BlockAppRoutes.editor(null))
    }

    @Test
    fun `existing editor route carries the block id`() {
        assertEquals("blocks/editor?blockId=abc-123", BlockAppRoutes.editor("abc-123"))
    }

    @Test
    fun `editor pattern declares the optional block id argument`() {
        assertEquals("blocks/editor?blockId={blockId}", BlockAppRoutes.EDITOR_PATTERN)
        assertEquals("blockId", BlockAppRoutes.EDITOR_BLOCK_ID_ARG)
    }

    @Test
    fun `bottom bar is visible only on usage and blocks`() {
        assertTrue(BlockAppRoutes.showsBottomBar(BlockAppRoutes.USAGE))
        assertTrue(BlockAppRoutes.showsBottomBar(BlockAppRoutes.BLOCKS))
        assertFalse(BlockAppRoutes.showsBottomBar(BlockAppRoutes.EDITOR_PATTERN))
        assertFalse(BlockAppRoutes.showsBottomBar(null))
    }
}

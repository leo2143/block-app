package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.MoveConflict
import org.junit.Assert.assertEquals
import org.junit.Test

class MoveMessagesTest {

    private fun conflict(
        label: String,
        fromBlockId: String = "B1",
        fromBlockName: String = "B1",
        willBeDeleted: Boolean = false,
    ) = MoveConflict(
        packageName = label.lowercase(),
        appLabel = label,
        fromBlockId = fromBlockId,
        fromBlockName = fromBlockName,
        fromBlockWillBeDeleted = willBeDeleted,
    )

    @Test
    fun `no conflicts gives no lines`() {
        assertEquals(emptyList<String>(), MoveMessages.build(emptyList()))
    }

    @Test
    fun `single app from a block that survives`() {
        assertEquals(
            listOf("¿Mover Instagram de B1 a este bloqueo?", "Su uso se reinicia."),
            MoveMessages.build(listOf(conflict("Instagram"))),
        )
    }

    @Test
    fun `several apps from one block are joined with commas and y`() {
        val lines = MoveMessages.build(listOf(conflict("Instagram"), conflict("X"), conflict("TikTok")))

        assertEquals("¿Mover Instagram, X y TikTok de B1 a este bloqueo?", lines.first())
    }

    @Test
    fun `a source that will be emptied adds the deletion line over the full set`() {
        val lines = MoveMessages.build(
            listOf(conflict("Instagram", willBeDeleted = true), conflict("X", willBeDeleted = true)),
        )

        assertEquals(
            listOf(
                "¿Mover Instagram y X de B1 a este bloqueo?",
                "B1 se eliminará porque quedará sin apps.",
                "Su uso se reinicia.",
            ),
            lines,
        )
    }

    @Test
    fun `conflicts are grouped by source block in first seen order`() {
        val lines = MoveMessages.build(
            listOf(
                conflict("Instagram", fromBlockId = "B1", fromBlockName = "Redes"),
                conflict("YouTube", fromBlockId = "B2", fromBlockName = "Video", willBeDeleted = true),
                conflict("X", fromBlockId = "B1", fromBlockName = "Redes"),
            ),
        )

        assertEquals(
            listOf(
                "¿Mover Instagram y X de Redes a este bloqueo?",
                "¿Mover YouTube de Video a este bloqueo?",
                "Video se eliminará porque quedará sin apps.",
                "Su uso se reinicia.",
            ),
            lines,
        )
    }

    @Test
    fun `the footer appears exactly once`() {
        val lines = MoveMessages.build(
            listOf(
                conflict("A", fromBlockId = "B1"),
                conflict("B", fromBlockId = "B2"),
                conflict("C", fromBlockId = "B3"),
            ),
        )

        assertEquals(1, lines.count { it == "Su uso se reinicia." })
        assertEquals("Su uso se reinicia.", lines.last())
    }
}

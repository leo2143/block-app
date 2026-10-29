package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockChangeSummary
import com.leo.blockapp.domain.model.BlockReference
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.RestoreBlockResult
import com.leo.blockapp.domain.model.SkippedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoreMessagesTest {

    private val block = Block(
        id = "B1",
        name = "Redes",
        packageNames = linkedSetOf("a"),
        rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60),
        position = 0,
    )
    private val summary = BlockChangeSummary(emptySet(), emptySet(), emptySet(), emptyMap(), emptyMap())

    private fun skipped(label: String, owner: String = "B2") =
        SkippedApp(packageName = label.lowercase(), label = label, ownerBlock = BlockReference("id-$owner", owner))

    @Test
    fun `one skipped app names the owner block`() {
        val result = RestoreBlockResult.PartiallyRestored(block, listOf(skipped("Instagram")), summary)

        assertEquals("Instagram ya está en otro bloqueo (B2)", RestoreMessages.message(result))
    }

    @Test
    fun `two skipped apps are joined with y`() {
        val result = RestoreBlockResult.PartiallyRestored(block, listOf(skipped("Instagram"), skipped("X")), summary)

        assertEquals("Instagram y X ya están en otros bloqueos", RestoreMessages.message(result))
    }

    @Test
    fun `three skipped apps use commas and a final y`() {
        val result = RestoreBlockResult.PartiallyRestored(
            block,
            listOf(skipped("Instagram"), skipped("X"), skipped("TikTok")),
            summary,
        )

        assertEquals("Instagram, X y TikTok ya están en otros bloqueos", RestoreMessages.message(result))
    }

    @Test
    fun `nothing restored says it could not be restored without a period`() {
        val result = RestoreBlockResult.NotRestored(listOf(skipped("Instagram")))

        assertEquals("No se pudo restaurar", RestoreMessages.message(result))
    }

    @Test
    fun `a fully restored block has no message`() {
        assertNull(RestoreMessages.message(RestoreBlockResult.Restored(block, summary)))
    }

    @Test
    fun `the retry snackbar copy keeps its period`() {
        assertEquals("No se pudo restaurar.", RestoreMessages.RETRY_PROMPT)
        assertEquals("No se pudo restaurar", RestoreMessages.NOT_RESTORED)
    }
}

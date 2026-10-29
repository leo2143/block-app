package com.leo.blockapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockTest {

    private val rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60)

    @Test
    fun `block is enabled by default`() {
        val block = Block(id = "B1", name = "Redes", packageNames = setOf("a"), rule = rule)
        assertEquals(true, block.enabled)
    }

    @Test
    fun `rule optional fields are off by default`() {
        assertEquals(null, rule.dailyCycleCap)
        assertEquals(null, rule.inactivityResetMinutes)
    }

    @Test
    fun `package order of a linked set is preserved`() {
        val ordered = linkedSetOf("zeta", "alfa", "mid")
        val block = Block(id = "B1", name = "Redes", packageNames = ordered, rule = rule)
        assertEquals(listOf("zeta", "alfa", "mid"), block.packageNames.toList())
    }

    @Test
    fun `results and summaries are plain data with value equality`() {
        val summary = BlockChangeSummary(
            affectedBlockIds = setOf("B1"),
            emptiedBlockIds = emptySet(),
            movedPackages = emptySet(),
            addedPackages = mapOf("B1" to setOf("a")),
            removedPackages = emptyMap(),
        )
        val block = Block("B1", "Redes", setOf("a"), rule)
        val owner = BlockReference("B1", "Redes")

        assertEquals(SaveBlockResult.Saved(block, summary), SaveBlockResult.Saved(block, summary))
        assertEquals(RestoreBlockResult.NotRestored(emptyList()), RestoreBlockResult.NotRestored(emptyList()))
        assertEquals(
            MoveConflict("a", "A", "B1", "Redes", fromBlockWillBeDeleted = true),
            MoveConflict("a", "A", "B1", "Redes", true),
        )
        assertEquals("B1", SelectableApp("a", "A", installed = true, ownerBlock = owner).ownerBlock?.id)
        assertEquals("A", SkippedApp("a", "A", owner).label)
    }
}

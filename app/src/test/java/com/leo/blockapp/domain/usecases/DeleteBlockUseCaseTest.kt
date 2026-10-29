package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockRepository
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.DeletedBlock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeleteBlockUseCaseTest {

    private val richRule = BlockRule.AccumulatedUsageWithCooldown(
        usageLimitMinutes = 25,
        cooldownMinutes = 45,
        dailyCycleCap = 3,
        inactivityResetMinutes = 30,
    )

    private fun block(id: String, vararg packageNames: String, enabled: Boolean = true) = Block(
        id = id,
        name = "Block $id",
        packageNames = linkedSetOf(*packageNames),
        rule = richRule,
        enabled = enabled,
    )

    @Test
    fun `returns the exact removed block and drops it from the repository`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "c", "a"), block("B2", "b")))

        val removed = DeleteBlockUseCase(repository)("B1")

        assertEquals(DeletedBlock(block("B1", "c", "a"), index = 0), removed)
        assertEquals(listOf("c", "a"), removed!!.block.packageNames.toList())
        assertEquals(listOf(block("B2", "b")), repository.getBlocks())
        assertEquals(1, repository.writes.size)
        assertEquals(setOf("B1"), repository.writes.single().deletedIds)
    }

    @Test
    fun `reports the position the block had in the list at delete time`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b"), block("B3", "c")))

        assertEquals(1, DeleteBlockUseCase(repository)("B2")!!.index)
        assertEquals(1, DeleteBlockUseCase(repository)("B3")!!.index)
        assertEquals(0, DeleteBlockUseCase(repository)("B1")!!.index)
    }

    @Test
    fun `deleting a paused block returns it with enabled false`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a", enabled = false)))

        assertEquals(DeletedBlock(block("B1", "a", enabled = false), index = 0), DeleteBlockUseCase(repository)("B1"))
        assertEquals(emptyList<Block>(), repository.getBlocks())
    }

    @Test
    fun `an unknown id returns null and writes nothing`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))

        assertNull(DeleteBlockUseCase(repository)("B9"))

        assertEquals(listOf(block("B1", "a")), repository.getBlocks())
        assertEquals(0, repository.writes.size)
    }

    @Test
    fun `the apps of the deleted block are no longer owned by any block`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a", "b"), block("B2", "c")))

        DeleteBlockUseCase(repository)("B1")

        assertEquals(setOf("c"), repository.getBlocks().flatMap { it.packageNames }.toSet())
    }

    @Test
    fun `two concurrent deletes of the same block remove it exactly once`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")), beforeApply = { yield() })
        val deleteBlock = DeleteBlockUseCase(repository)

        val results = listOf(async { deleteBlock("B1") }, async { deleteBlock("B1") }).awaitAll()

        assertEquals(listOf(DeletedBlock(block("B1", "a"), index = 0)), results.filterNotNull())
        assertEquals(1, results.count { it == null })
        assertEquals(1, repository.writes.size)
    }
}

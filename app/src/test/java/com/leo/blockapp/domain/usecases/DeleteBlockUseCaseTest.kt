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

        assertEquals(DeletedBlock(block("B1", "c", "a"), emptyList(), listOf("B2")), removed)
        assertEquals(listOf("c", "a"), removed!!.block.packageNames.toList())
        assertEquals(listOf(block("B2", "b")), repository.getBlocks())
        assertEquals(1, repository.writes.size)
        assertEquals(setOf("B1"), repository.writes.single().deletedIds)
    }

    @Test
    fun `reports the ids before and after the block in list order at delete time`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b"), block("B3", "c")))

        val middle = DeleteBlockUseCase(repository)("B2")!!
        assertEquals(listOf("B1"), middle.precedingBlockIds)
        assertEquals(listOf("B3"), middle.followingBlockIds)

        val last = DeleteBlockUseCase(repository)("B3")!!
        assertEquals(listOf("B1"), last.precedingBlockIds)
        assertEquals(emptyList<String>(), last.followingBlockIds)

        val first = DeleteBlockUseCase(repository)("B1")!!
        assertEquals(emptyList<String>(), first.precedingBlockIds)
        assertEquals(emptyList<String>(), first.followingBlockIds)
    }

    @Test
    fun `neighbor ids keep the full list order on both sides`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b"), block("B3", "c"), block("B4", "d")))

        val deleted = DeleteBlockUseCase(repository)("B3")!!

        assertEquals(listOf("B1", "B2"), deleted.precedingBlockIds)
        assertEquals(listOf("B4"), deleted.followingBlockIds)
    }

    @Test
    fun `deleting a paused block returns it with enabled false`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a", enabled = false)))

        assertEquals(
            DeletedBlock(block("B1", "a", enabled = false), emptyList(), emptyList()),
            DeleteBlockUseCase(repository)("B1"),
        )
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

        assertEquals(listOf(DeletedBlock(block("B1", "a"), emptyList(), emptyList())), results.filterNotNull())
        assertEquals(1, results.count { it == null })
        assertEquals(1, repository.writes.size)
    }
}

package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeBlockRepositoryTest {

    private fun block(id: String, vararg packageNames: String) = Block(
        id = id,
        name = "Block $id",
        packageNames = linkedSetOf(*packageNames),
        rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60),
    )

    @Test
    fun `empty changes perform no write`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))

        val result = repository.update { current -> BlockUpdate(BlockChanges(), current.size) }

        assertEquals(1, result)
        assertEquals(0, repository.writes.size)
        assertEquals(listOf(block("B1", "a")), repository.getBlocks())
    }

    @Test
    fun `upserts replace by id in place and append new blocks`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b")))

        repository.update {
            BlockUpdate(BlockChanges(upserts = listOf(block("B1", "a", "c"), block("B3", "d"))), Unit)
        }

        assertEquals(
            listOf(block("B1", "a", "c"), block("B2", "b"), block("B3", "d")),
            repository.getBlocks(),
        )
        assertEquals(1, repository.writes.size)
    }

    @Test
    fun `deletions and upserts apply together`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b")))

        repository.update {
            BlockUpdate(BlockChanges(upserts = listOf(block("B3", "a")), deletedIds = setOf("B1")), Unit)
        }

        assertEquals(listOf(block("B2", "b"), block("B3", "a")), repository.getBlocks())
    }

    @Test
    fun `concurrent updates are serialized so none loses the other`() = runTest {
        val repository = FakeBlockRepository(emptyList())

        List(20) { index ->
            async {
                repository.update { current ->
                    BlockUpdate(BlockChanges(upserts = listOf(block("B$index", "p$index"))), current.size)
                }
            }
        }.awaitAll()

        assertEquals(20, repository.getBlocks().size)
        assertEquals(20, repository.writes.size)
    }
}

package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

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
    fun `deletion wins when an id is both upserted and deleted`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b")))

        repository.update {
            BlockUpdate(BlockChanges(upserts = listOf(block("B1", "z"), block("B3", "c")), deletedIds = setOf("B1", "B3")), Unit)
        }

        assertEquals(listOf(block("B2", "b")), repository.getBlocks())
    }

    @Test
    fun `upsert of a missing id appends it at the end in the given order`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))

        repository.update {
            BlockUpdate(BlockChanges(upserts = listOf(block("B3", "c"), block("B2", "b"))), Unit)
        }

        assertEquals(listOf("B1", "B3", "B2"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `insertAt places a new id at that index of the list after deletions`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b"), block("B3", "c")))

        repository.update {
            BlockUpdate(
                BlockChanges(upserts = listOf(block("B4", "d")), deletedIds = setOf("B1"), insertAt = mapOf("B4" to 1)),
                Unit,
            )
        }

        assertEquals(listOf("B2", "B4", "B3"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `insertAt is clamped to the list bounds`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b")))

        repository.update {
            BlockUpdate(
                BlockChanges(
                    upserts = listOf(block("B3", "c"), block("B4", "d")),
                    insertAt = mapOf("B3" to 99, "B4" to -5),
                ),
                Unit,
            )
        }

        assertEquals(listOf("B4", "B1", "B2", "B3"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `insertAt is ignored for ids that are already stored and for ids without an entry`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b")))

        repository.update {
            BlockUpdate(
                BlockChanges(upserts = listOf(block("B2", "z"), block("B3", "c")), insertAt = mapOf("B2" to 0)),
                Unit,
            )
        }

        assertEquals(listOf("B1", "B2", "B3"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `update calls never overlap`() = runTest {
        val repository = FakeBlockRepository(emptyList(), beforeApply = { yield() })

        List(10) { index ->
            async { repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B$index", "p$index"))), Unit) } }
        }.awaitAll()

        assertEquals(1, repository.maxConcurrentUpdates)
    }

    @Test
    fun `upsert of an existing id keeps its position`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b"), block("B3", "c")))

        repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B2", "z"))), Unit) }

        assertEquals(listOf("B1", "B2", "B3"), repository.getBlocks().map { it.id })
        assertEquals(listOf("z"), repository.getBlocks()[1].packageNames.toList())
    }

    @Test
    fun `duplicate ids in upserts are rejected and nothing is written`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.update {
                    BlockUpdate(BlockChanges(upserts = listOf(block("B2", "b"), block("B2", "c"))), Unit)
                }
            }
        }

        assertEquals(listOf(block("B1", "a")), repository.getBlocks())
        assertEquals(0, repository.writes.size)
    }

    @Test
    fun `concurrent updates are serialized so none loses the other`() = runTest {
        // yield() inside the read-then-write window forces interleaving unless the lock serializes updates.
        val repository = FakeBlockRepository(emptyList(), beforeApply = { yield() })

        val sizesSeen = List(20) { index ->
            async {
                repository.update { current ->
                    BlockUpdate(BlockChanges(upserts = listOf(block("B$index", "p$index"))), current.size)
                }
            }
        }.awaitAll()

        assertEquals((0 until 20).toList(), sizesSeen.sorted())
        assertEquals(20, repository.getBlocks().size)
        assertEquals(20, repository.writes.size)
    }

    @Test
    fun `injected write failure propagates and leaves blocks and writes untouched`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))
        repository.writeFailure = IOException("disk full")

        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B2", "b"))), Unit) }
            }
        }

        assertEquals("disk full", thrown.message)
        assertEquals(listOf(block("B1", "a")), repository.getBlocks())
        assertEquals(0, repository.writes.size)
    }

    @Test
    fun `injected write failure does not affect updates that change nothing`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))
        repository.writeFailure = IOException("disk full")

        val result = repository.update { BlockUpdate(BlockChanges(), "no write") }

        assertEquals("no write", result)
    }

    @Test
    fun `writes succeed again after the injected failure is cleared`() = runTest {
        val repository = FakeBlockRepository()
        repository.writeFailure = IOException("disk full")
        repository.writeFailure = null

        repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B1", "a"))), Unit) }

        assertEquals(listOf(block("B1", "a")), repository.getBlocks())
    }
}

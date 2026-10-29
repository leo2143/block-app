package com.leo.blockapp.domain.ports.output

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The [BlockRepository] / [BlockChanges] contract as executable scenarios. Every implementation (the in-memory
 * fake and the DataStore adapter) extends this class so both honor exactly the same rules.
 */
abstract class BlockRepositoryContract {

    /** A fresh, empty repository. Called once per test. */
    protected abstract fun createRepository(): BlockRepository

    protected fun block(
        id: String,
        vararg packageNames: String,
        position: Long = id.removePrefix("B").toLong(),
    ) = Block(
        id = id,
        name = "Block $id",
        packageNames = linkedSetOf(*packageNames),
        rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60),
        position = position,
    )

    /** Creates a repository already holding [blocks] (given in (position, id) order). */
    protected suspend fun repositoryWith(vararg blocks: Block): BlockRepository {
        val repository = createRepository()
        if (blocks.isNotEmpty()) {
            repository.update { BlockUpdate(BlockChanges(upserts = blocks.toList()), Unit) }
        }
        return repository
    }

    @Test
    fun `empty changes leave the blocks untouched and return the transform result`() = runTest {
        val repository = repositoryWith(block("B1", "a"))

        val result = repository.update { current -> BlockUpdate(BlockChanges(), current.size) }

        assertEquals(1, result)
        assertEquals(listOf(block("B1", "a")), repository.getBlocks())
    }

    @Test
    fun `an empty repository returns an empty list`() = runTest {
        assertEquals(emptyList<Block>(), createRepository().getBlocks())
    }

    @Test
    fun `upserts replace by id in place and append new blocks`() = runTest {
        val repository = repositoryWith(block("B1", "a"), block("B2", "b"))

        repository.update {
            BlockUpdate(BlockChanges(upserts = listOf(block("B1", "a", "c"), block("B3", "d"))), Unit)
        }

        assertEquals(
            listOf(block("B1", "a", "c"), block("B2", "b"), block("B3", "d")),
            repository.getBlocks(),
        )
    }

    @Test
    fun `deletions and upserts apply together`() = runTest {
        val repository = repositoryWith(block("B1", "a"), block("B2", "b"))

        repository.update {
            BlockUpdate(BlockChanges(upserts = listOf(block("B3", "a")), deletedIds = setOf("B1")), Unit)
        }

        assertEquals(listOf(block("B2", "b"), block("B3", "a")), repository.getBlocks())
    }

    @Test
    fun `deletion wins when an id is both upserted and deleted`() = runTest {
        val repository = repositoryWith(block("B1", "a"), block("B2", "b"))

        repository.update {
            BlockUpdate(BlockChanges(upserts = listOf(block("B1", "z"), block("B3", "c")), deletedIds = setOf("B1", "B3")), Unit)
        }

        assertEquals(listOf(block("B2", "b")), repository.getBlocks())
    }

    @Test
    fun `deleting ids that are not stored is ignored`() = runTest {
        val repository = repositoryWith(block("B1", "a"))

        repository.update { BlockUpdate(BlockChanges(deletedIds = setOf("B9")), Unit) }

        assertEquals(listOf(block("B1", "a")), repository.getBlocks())
    }

    @Test
    fun `upsert of a missing id appends it at the end in the given order`() = runTest {
        val repository = repositoryWith(block("B1", "a"))

        repository.update {
            BlockUpdate(BlockChanges(upserts = listOf(block("B2", "b"), block("B3", "c"))), Unit)
        }

        assertEquals(listOf("B1", "B2", "B3"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `a write that breaks the position and id order is rejected and nothing is written`() = runTest {
        val repository = repositoryWith(block("B1", "a"), block("B2", "b"))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B0", "c", position = 0))), Unit) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B2", "b", position = 0))), Unit) }
            }
        }

        assertEquals(listOf(block("B1", "a"), block("B2", "b")), repository.getBlocks())
    }

    @Test
    fun `the repository keeps working after a rejected write`() = runTest {
        val repository = repositoryWith(block("B1", "a"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B0", "c", position = 0))), Unit) }
            }
        }

        repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B2", "b"))), Unit) }

        assertEquals(listOf("B1", "B2"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `equal positions are ordered by id`() = runTest {
        val repository = repositoryWith(block("B1", "a", position = 1))

        repository.update {
            BlockUpdate(BlockChanges(upserts = listOf(block("B2", "b", position = 1)), insertAt = mapOf("B2" to 1)), Unit)
        }

        assertEquals(listOf("B1", "B2"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `insertAt places a new id at that index of the list after deletions`() = runTest {
        val repository = repositoryWith(block("B1", "a"), block("B2", "b"), block("B3", "c"))

        repository.update {
            BlockUpdate(
                BlockChanges(
                    upserts = listOf(block("B4", "d", position = 2)),
                    deletedIds = setOf("B1"),
                    insertAt = mapOf("B4" to 1),
                ),
                Unit,
            )
        }

        assertEquals(listOf("B2", "B4", "B3"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `insertAt is clamped to the list bounds`() = runTest {
        val repository = repositoryWith(block("B1", "a"), block("B2", "b"))

        repository.update {
            BlockUpdate(
                BlockChanges(
                    upserts = listOf(block("B3", "c"), block("B0", "d")),
                    insertAt = mapOf("B3" to 99, "B0" to -5),
                ),
                Unit,
            )
        }

        assertEquals(listOf("B0", "B1", "B2", "B3"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `insertAt is ignored for ids that are already stored and for ids without an entry`() = runTest {
        val repository = repositoryWith(block("B1", "a"), block("B2", "b"))

        repository.update {
            BlockUpdate(
                BlockChanges(upserts = listOf(block("B2", "z"), block("B3", "c")), insertAt = mapOf("B2" to 0)),
                Unit,
            )
        }

        assertEquals(listOf("B1", "B2", "B3"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `upsert of an existing id keeps its position in the list`() = runTest {
        val repository = repositoryWith(block("B1", "a"), block("B2", "b"), block("B3", "c"))

        repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B2", "z"))), Unit) }

        assertEquals(listOf("B1", "B2", "B3"), repository.getBlocks().map { it.id })
        assertEquals(listOf("z"), repository.getBlocks()[1].packageNames.toList())
    }

    @Test
    fun `package selection order survives a write`() = runTest {
        val repository = createRepository()

        repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B1", "z", "a", "m"))), Unit) }

        assertEquals(listOf("z", "a", "m"), repository.getBlocks().single().packageNames.toList())
    }

    @Test
    fun `duplicate ids in upserts are rejected and nothing is written`() = runTest {
        val repository = repositoryWith(block("B1", "a"))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.update {
                    BlockUpdate(BlockChanges(upserts = listOf(block("B2", "b"), block("B2", "c"))), Unit)
                }
            }
        }

        assertEquals(listOf(block("B1", "a")), repository.getBlocks())
    }

    @Test
    fun `the transform sees the stored blocks ordered by position and id`() = runTest {
        val repository = repositoryWith(block("B1", "a"), block("B2", "b", position = 1), block("B3", "c"))

        val seen = repository.update { current -> BlockUpdate(BlockChanges(), current.map { it.id }) }

        assertEquals(listOf("B1", "B2", "B3"), seen)
    }

    @Test
    fun `concurrent updates are serialized so none loses the other`() = runTest {
        val repository = createRepository()

        val sizesSeen = List(20) { index ->
            async {
                repository.update { current ->
                    val position = index.toLong()
                    val insertIndex = current.count { it.position < position }
                    BlockUpdate(
                        BlockChanges(
                            upserts = listOf(block("B$index", "p$index", position = position)),
                            insertAt = mapOf("B$index" to insertIndex),
                        ),
                        current.size,
                    )
                }
            }
        }.awaitAll()

        assertEquals((0 until 20).toList(), sizesSeen.sorted())
        assertEquals((0 until 20).map { "B$it" }, repository.getBlocks().map { it.id })
    }
}

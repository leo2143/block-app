package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockRepositoryContract
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

/** Runs the shared repository contract against the fake, plus the fake's own recording and failure-injection hooks. */
class FakeBlockRepositoryTest : BlockRepositoryContract() {

    override fun createRepository(): BlockRepository = FakeBlockRepository()

    @Test
    fun `empty changes perform no write`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))

        val result = repository.update { current -> BlockUpdate(BlockChanges(), current.size) }

        assertEquals(1, result)
        assertEquals(0, repository.writes.size)
    }

    @Test
    fun `a real write is recorded once`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))

        repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B2", "b"))), Unit) }

        assertEquals(1, repository.writes.size)
    }

    @Test
    fun `a rejected write is not recorded`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B0", "c", position = 0))), Unit) }
            }
        }

        assertEquals(0, repository.writes.size)
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
    fun `concurrent updates inside the read-then-write window are serialized`() = runTest {
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

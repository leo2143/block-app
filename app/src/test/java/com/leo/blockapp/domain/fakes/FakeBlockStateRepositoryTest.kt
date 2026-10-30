package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.BlockStateRepositoryContract
import com.leo.blockapp.domain.ports.output.BlockStateUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** Runs the shared state repository contract against the fake, plus the fake's own recording and failure hooks. */
class FakeBlockStateRepositoryTest : BlockStateRepositoryContract() {

    override fun createRepository(): BlockStateRepository = FakeBlockStateRepository()

    override fun createInterleavingRepository(): BlockStateRepository = FakeBlockStateRepository(beforeApply = { yield() })

    @Test
    fun `empty updates perform no write`() = runTest {
        val repository = FakeBlockStateRepository(mapOf("B1" to state("One")))

        val result = repository.update { current -> BlockStateUpdate(result = current.size) }

        assertEquals(1, result)
        assertEquals(0, repository.writes.size)
    }

    @Test
    fun `a real write is recorded once`() = runTest {
        val repository = FakeBlockStateRepository()

        repository.update { BlockStateUpdate(upserts = mapOf("B1" to state("One")), result = Unit) }

        assertEquals(1, repository.writes.size)
    }

    @Test
    fun `update calls never overlap`() = runTest {
        val repository = FakeBlockStateRepository(beforeApply = { yield() })

        List(10) { index ->
            async { repository.update { BlockStateUpdate(upserts = mapOf("B$index" to state()), result = Unit) } }
        }.awaitAll()

        assertEquals(1, repository.maxConcurrentUpdates)
    }

    @Test
    fun `concurrent updates inside the read-then-write window are serialized`() = runTest {
        // yield() inside the read-then-write window forces interleaving unless the lock serializes updates.
        val repository = FakeBlockStateRepository(beforeApply = { yield() })

        val sizesSeen = List(20) { index ->
            async {
                repository.update { current ->
                    BlockStateUpdate(upserts = mapOf("B$index" to state()), result = current.size)
                }
            }
        }.awaitAll()

        assertEquals((0 until 20).toList(), sizesSeen.sorted())
        assertEquals(20, repository.observeStates().first().size)
        assertEquals(20, repository.writes.size)
    }

    @Test
    fun `injected write failure propagates and leaves states and writes untouched`() = runTest {
        val repository = FakeBlockStateRepository(mapOf("B1" to state("One")))
        repository.writeFailure = IOException("disk full")

        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                repository.update { BlockStateUpdate(upserts = mapOf("B2" to state("Two")), result = Unit) }
            }
        }

        assertEquals("disk full", thrown.message)
        assertEquals(mapOf("B1" to state("One")), repository.observeStates().first())
        assertEquals(0, repository.writes.size)
    }

    @Test
    fun `injected write failure does not affect updates that change nothing`() = runTest {
        val repository = FakeBlockStateRepository()
        repository.writeFailure = IOException("disk full")

        val result = repository.update { BlockStateUpdate(result = "no write") }

        assertEquals("no write", result)
    }
}

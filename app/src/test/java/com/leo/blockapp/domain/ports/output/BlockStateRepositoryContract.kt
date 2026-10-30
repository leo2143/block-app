package com.leo.blockapp.domain.ports.output

import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The [BlockStateRepository] / [BlockStateUpdate] contract as executable scenarios. Every implementation (the
 * in-memory fake and the DataStore adapter) extends this class so both honor exactly the same rules.
 */
abstract class BlockStateRepositoryContract {

    /** A fresh, empty repository. Called once per test. */
    protected abstract fun createRepository(): BlockStateRepository

    protected fun state(
        blockName: String = "Block",
        cyclesToday: Int = 0,
        cooldownEndMillis: Long? = null,
        holdUntilMillis: Long? = null,
        holdReason: BlockReason? = null,
        holdPackageNames: Set<String> = emptySet(),
    ) = BlockState(
        cycleStartMillis = 1_000L,
        cooldownEndMillis = cooldownEndMillis,
        cyclesToday = cyclesToday,
        cyclesDayStartMillis = 500L,
        holdUntilMillis = holdUntilMillis,
        holdReason = holdReason,
        holdPackageNames = holdPackageNames,
        blockName = blockName,
    )

    /** Creates a repository already holding [states]. */
    protected suspend fun repositoryWith(states: Map<String, BlockState>): BlockStateRepository {
        val repository = createRepository()
        if (states.isNotEmpty()) {
            repository.update { BlockStateUpdate(upserts = states, result = Unit) }
        }
        return repository
    }

    @Test
    fun `an empty repository observes an empty map`() = runTest {
        assertEquals(emptyMap<String, BlockState>(), createRepository().observeStates().first())
    }

    @Test
    fun `upserts are stored and observed`() = runTest {
        val repository = createRepository()

        repository.update { BlockStateUpdate(upserts = mapOf("B1" to state("One"), "B2" to state("Two")), result = Unit) }

        assertEquals(mapOf("B1" to state("One"), "B2" to state("Two")), repository.observeStates().first())
    }

    @Test
    fun `an upsert replaces the stored state with the same id and keeps the others`() = runTest {
        val repository = repositoryWith(mapOf("B1" to state("One"), "B2" to state("Two")))

        repository.update { BlockStateUpdate(upserts = mapOf("B1" to state("One", cyclesToday = 3)), result = Unit) }

        assertEquals(
            mapOf("B1" to state("One", cyclesToday = 3), "B2" to state("Two")),
            repository.observeStates().first(),
        )
    }

    @Test
    fun `deleted ids are removed`() = runTest {
        val repository = repositoryWith(mapOf("B1" to state("One"), "B2" to state("Two")))

        repository.update { BlockStateUpdate<Unit>(deletedIds = setOf("B1"), result = Unit) }

        assertEquals(mapOf("B2" to state("Two")), repository.observeStates().first())
    }

    @Test
    fun `deletion wins when an id is both upserted and deleted`() = runTest {
        val repository = repositoryWith(mapOf("B1" to state("One")))

        repository.update {
            BlockStateUpdate(upserts = mapOf("B1" to state("Changed"), "B3" to state("Three")), deletedIds = setOf("B1", "B3"), result = Unit)
        }

        assertEquals(emptyMap<String, BlockState>(), repository.observeStates().first())
    }

    @Test
    fun `deleting ids that are not stored is ignored`() = runTest {
        val repository = repositoryWith(mapOf("B1" to state("One")))

        repository.update { BlockStateUpdate<Unit>(deletedIds = setOf("B9"), result = Unit) }

        assertEquals(mapOf("B1" to state("One")), repository.observeStates().first())
    }

    @Test
    fun `an empty update returns the transform result and leaves the states untouched`() = runTest {
        val repository = repositoryWith(mapOf("B1" to state("One")))

        val result = repository.update { current -> BlockStateUpdate(result = current.size) }

        assertEquals(1, result)
        assertEquals(mapOf("B1" to state("One")), repository.observeStates().first())
    }

    @Test
    fun `the transform sees the stored states`() = runTest {
        val repository = repositoryWith(mapOf("B1" to state("One"), "B2" to state("Two")))

        val seen = repository.update { current -> BlockStateUpdate(result = current.keys) }

        assertEquals(setOf("B1", "B2"), seen)
    }

    @Test
    fun `every field survives a write including null and hold fields`() = runTest {
        val full = state(
            blockName = "Full",
            cyclesToday = 2,
            cooldownEndMillis = 9_000L,
            holdUntilMillis = 9_500L,
            holdReason = BlockReason.DAILY_CAP,
            holdPackageNames = linkedSetOf("z", "a"),
        )
        val repository = createRepository()

        repository.update { BlockStateUpdate(upserts = mapOf("B1" to full, "B2" to state()), result = Unit) }

        assertEquals(mapOf("B1" to full, "B2" to state()), repository.observeStates().first())
    }

    @Test
    fun `concurrent updates are serialized so none loses the other`() = runTest {
        val repository = repositoryWith(mapOf("B1" to state(cyclesToday = 0)))

        List(20) {
            async {
                repository.update { current ->
                    val counter = checkNotNull(current["B1"])
                    BlockStateUpdate(upserts = mapOf("B1" to counter.copy(cyclesToday = counter.cyclesToday + 1)), result = Unit)
                }
            }
        }.awaitAll()

        assertEquals(20, repository.observeStates().first().getValue("B1").cyclesToday)
    }
}

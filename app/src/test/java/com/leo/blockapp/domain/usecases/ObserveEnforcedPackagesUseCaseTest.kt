package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockRepository
import com.leo.blockapp.domain.fakes.FakeBlockStateRepository
import com.leo.blockapp.domain.fakes.FakeClock
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.BlockStateUpdate
import com.leo.blockapp.domain.ports.output.BlockUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveEnforcedPackagesUseCaseTest {

    private val minute = 60_000L
    private val startOfToday = 10 * 24 * 60 * minute
    private val now = startOfToday + 12 * 60 * minute
    private val clock = FakeClock(nowMillis = now)

    private val instagram = "com.instagram.android"
    private val x = "com.twitter.android"
    private val tiktok = "com.tiktok"

    private val rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60)

    private fun block(id: String, vararg packageNames: String, enabled: Boolean = true) = Block(
        id = id,
        name = "Social $id",
        packageNames = linkedSetOf(*packageNames),
        rule = rule,
        position = id.removePrefix("B").toLong(),
        enabled = enabled,
    )

    private fun heldState(untilMillis: Long, vararg packageNames: String) =
        BlockState.initial("Held", startOfToday).copy(
            holdUntilMillis = untilMillis,
            holdReason = BlockReason.DAILY_CAP,
            holdPackageNames = setOf(*packageNames),
        )

    private fun TestScope.collectInto(
        emissions: MutableList<Set<String>>,
        blockRepository: FakeBlockRepository,
        stateRepository: BlockStateRepository,
    ) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ObserveEnforcedPackagesUseCase(blockRepository, stateRepository, clock)().toList(emissions)
        }
    }

    @Test
    fun `emits the packages of the enabled blocks only`() = runTest {
        val emissions = mutableListOf<Set<String>>()
        collectInto(
            emissions,
            FakeBlockRepository(listOf(block("B1", instagram), block("B2", x, enabled = false))),
            FakeBlockStateRepository(),
        )

        assertEquals(listOf(setOf(instagram)), emissions)
    }

    @Test
    fun `adds the packages of live holds, even from paused or deleted blocks, and skips ended holds`() = runTest {
        val emissions = mutableListOf<Set<String>>()
        collectInto(
            emissions,
            FakeBlockRepository(listOf(block("B1", instagram), block("B2", x, enabled = false))),
            FakeBlockStateRepository(
                mapOf(
                    "B2" to heldState(now + minute, x),
                    "B9" to heldState(now + minute, tiktok),
                    "B8" to heldState(now, "com.ended.app"),
                ),
            ),
        )

        assertEquals(listOf(setOf(instagram, x, tiktok)), emissions)
    }

    @Test
    fun `a running cooldown holds its packages too`() = runTest {
        val emissions = mutableListOf<Set<String>>()
        val cooling = BlockState.initial("Cooling", startOfToday).copy(
            cooldownEndMillis = now + minute,
            holdPackageNames = setOf(tiktok),
        )
        collectInto(emissions, FakeBlockRepository(), FakeBlockStateRepository(mapOf("B9" to cooling)))

        assertEquals(listOf(setOf(tiktok)), emissions)
    }

    @Test
    fun `emits again when a block is paused or a hold starts, and not when nothing changes`() = runTest {
        val blockRepository = FakeBlockRepository(listOf(block("B1", instagram), block("B2", x)))
        val stateRepository = FakeBlockStateRepository()
        val emissions = mutableListOf<Set<String>>()
        collectInto(emissions, blockRepository, stateRepository)

        blockRepository.update {
            BlockUpdate(BlockChanges(upserts = listOf(block("B2", x, enabled = false))), Unit)
        }
        stateRepository.update {
            BlockStateUpdate(upserts = mapOf("B2" to heldState(now + minute, x)), result = Unit)
        }
        stateRepository.update {
            // Same enforced set: the hold only gets a longer until.
            BlockStateUpdate(upserts = mapOf("B2" to heldState(now + 2 * minute, x)), result = Unit)
        }

        assertEquals(
            listOf(setOf(instagram, x), setOf(instagram), setOf(instagram, x)),
            emissions,
        )
    }

    private class ManualStateRepository : BlockStateRepository {
        val states = MutableSharedFlow<Map<String, BlockState>>(replay = 1)
        override fun observeStates(): Flow<Map<String, BlockState>> = states
        override suspend fun <T> update(transform: (current: Map<String, BlockState>) -> BlockStateUpdate<T>): T =
            error("not used")
    }

    @Test
    fun `waits for the first states instead of emitting a set without holds`() = runTest {
        val stateRepository = ManualStateRepository()
        val emissions = mutableListOf<Set<String>>()
        collectInto(emissions, FakeBlockRepository(listOf(block("B1", instagram))), stateRepository)

        assertEquals(emptyList<Set<String>>(), emissions)

        stateRepository.states.emit(mapOf("B9" to heldState(now + minute, tiktok)))

        assertEquals(listOf(setOf(instagram, tiktok)), emissions)
    }
}

package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockStateRepository
import com.leo.blockapp.domain.model.BlockListStatus
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.BlockStateUpdate
import com.leo.blockapp.domain.ports.output.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveBlockStatusesUseCaseTest {

    private val minute = 60_000L
    private val startOfToday = 10 * 24 * 60 * minute
    private val startOfNextDay = startOfToday + 24 * 60 * minute
    private val startMillis = startOfToday + 12 * 60 * minute

    /** "Now" is the start instant plus the virtual time of the test, so scheduled work and the clock agree. */
    private fun TestScope.virtualClock() = object : Clock {
        override fun nowMillis() = startMillis + testScheduler.currentTime
        override fun startOfDayMillis(timestampMillis: Long) = startOfToday
    }

    private fun state(
        name: String = "Name",
        cooldownEnd: Long? = null,
        holdUntil: Long? = null,
        holdReason: BlockReason? = null,
    ) = BlockState(
        cycleStartMillis = startOfToday,
        cooldownEndMillis = cooldownEnd,
        cyclesToday = 1,
        cyclesDayStartMillis = startOfToday,
        holdUntilMillis = holdUntil,
        holdReason = holdReason,
        holdPackageNames = setOf("pkg"),
        blockName = name,
    )

    private fun TestScope.collect(
        repository: BlockStateRepository,
    ): List<Map<String, BlockListStatus>> {
        val emissions = mutableListOf<Map<String, BlockListStatus>>()
        val useCase = ObserveBlockStatusesUseCase(repository, virtualClock())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { useCase().toList(emissions) }
        return emissions
    }

    @Test
    fun `no states means no status lines`() = runTest {
        val emissions = collect(FakeBlockStateRepository())
        runCurrent()

        assertEquals(listOf(emptyMap<String, BlockListStatus>()), emissions)
    }

    @Test
    fun `a running cooldown is reported with its end`() = runTest {
        val until = startMillis + 30 * minute
        val emissions = collect(FakeBlockStateRepository(mapOf("B1" to state(cooldownEnd = until))))
        runCurrent()

        assertEquals(listOf(mapOf("B1" to BlockListStatus.CoolingDown(until))), emissions)
    }

    @Test
    fun `an active daily cap hold is reported until the end of the day`() = runTest {
        val repository = FakeBlockStateRepository(
            mapOf("B1" to state(holdUntil = startOfNextDay, holdReason = BlockReason.DAILY_CAP)),
        )
        val emissions = collect(repository)
        runCurrent()

        assertEquals(listOf(mapOf("B1" to BlockListStatus.DailyCapReached(startOfNextDay))), emissions)
    }

    @Test
    fun `a state with nothing active has no line`() = runTest {
        val expiredHold = state(holdUntil = startMillis - minute, holdReason = BlockReason.COOLDOWN)
        val endedCooldown = state(cooldownEnd = startMillis - 1)
        val emissions = collect(FakeBlockStateRepository(mapOf("B1" to expiredHold, "B2" to endedCooldown)))
        runCurrent()

        assertEquals(listOf(emptyMap<String, BlockListStatus>()), emissions)
    }

    @Test
    fun `a hold until exactly now is already over`() = runTest {
        val emissions = collect(FakeBlockStateRepository(mapOf("B1" to state(cooldownEnd = startMillis))))
        runCurrent()

        assertEquals(listOf(emptyMap<String, BlockListStatus>()), emissions)
    }

    @Test
    fun `a live hold with no reason fails closed as a daily cap, like the enforcement`() = runTest {
        val until = startMillis + 5 * minute
        val emissions = collect(FakeBlockStateRepository(mapOf("B1" to state(holdUntil = until))))
        runCurrent()

        assertEquals(listOf(mapOf("B1" to BlockListStatus.DailyCapReached(until))), emissions)
    }

    @Test
    fun `a running cooldown shows before a longer cap hold and the cap shows after it`() = runTest {
        val cooldownEnd = startMillis + 10 * minute
        val repository = FakeBlockStateRepository(
            mapOf(
                "B1" to state(
                    cooldownEnd = cooldownEnd,
                    holdUntil = startOfNextDay,
                    holdReason = BlockReason.DAILY_CAP,
                ),
            ),
        )
        val emissions = collect(repository)
        advanceTimeBy(10 * minute + 1)
        runCurrent()

        assertEquals(
            listOf(
                mapOf("B1" to BlockListStatus.CoolingDown(cooldownEnd)),
                mapOf("B1" to BlockListStatus.DailyCapReached(startOfNextDay)),
            ),
            emissions,
        )
    }

    @Test
    fun `the line is removed when the cooldown ends, without polling`() = runTest {
        val until = startMillis + 10 * minute
        val emissions = collect(FakeBlockStateRepository(mapOf("B1" to state(cooldownEnd = until))))
        advanceTimeBy(10 * minute - 1)
        runCurrent()

        assertEquals(listOf(mapOf("B1" to BlockListStatus.CoolingDown(until))), emissions)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(
            listOf(mapOf("B1" to BlockListStatus.CoolingDown(until)), emptyMap<String, BlockListStatus>()),
            emissions,
        )
        advanceTimeBy(10 * 60 * minute)
        runCurrent()
        assertEquals(2, emissions.size)
    }

    @Test
    fun `it wakes up at the earliest end of several holds`() = runTest {
        val soon = startMillis + 5 * minute
        val later = startMillis + 20 * minute
        val repository = FakeBlockStateRepository(
            mapOf("B1" to state(cooldownEnd = later), "B2" to state(cooldownEnd = soon)),
        )
        val emissions = collect(repository)
        advanceTimeBy(5 * minute)
        runCurrent()
        advanceTimeBy(15 * minute)
        runCurrent()

        assertEquals(
            listOf(
                mapOf("B1" to BlockListStatus.CoolingDown(later), "B2" to BlockListStatus.CoolingDown(soon)),
                mapOf("B1" to BlockListStatus.CoolingDown(later)),
                emptyMap<String, BlockListStatus>(),
            ),
            emissions,
        )
    }

    @Test
    fun `a new state replaces the pending wake up`() = runTest {
        val until = startMillis + 30 * minute
        val repository = FakeBlockStateRepository(mapOf("B1" to state(cooldownEnd = until)))
        val emissions = collect(repository)
        advanceTimeBy(5 * minute)
        repository.update { BlockStateUpdate(upserts = mapOf("B1" to state(cooldownEnd = null)), result = Unit) }
        runCurrent()
        advanceTimeBy(60 * minute)
        runCurrent()

        assertEquals(
            listOf(mapOf("B1" to BlockListStatus.CoolingDown(until)), emptyMap<String, BlockListStatus>()),
            emissions,
        )
    }

    @Test
    fun `a state change that keeps the same lines does not emit again`() = runTest {
        val until = startMillis + 30 * minute
        val repository = FakeBlockStateRepository(mapOf("B1" to state(cooldownEnd = until)))
        val emissions = collect(repository)
        runCurrent()
        repository.update {
            BlockStateUpdate(upserts = mapOf("B1" to state(name = "Renamed", cooldownEnd = until)), result = Unit)
        }
        runCurrent()

        assertEquals(listOf(mapOf("B1" to BlockListStatus.CoolingDown(until))), emissions)
    }

    @Test
    fun `a hold of a deleted block still has a line keyed by its id`() = runTest {
        val until = startMillis + 30 * minute
        val emissions = collect(FakeBlockStateRepository(mapOf("gone" to state(cooldownEnd = until))))
        runCurrent()

        assertEquals(listOf(mapOf("gone" to BlockListStatus.CoolingDown(until))), emissions)
    }

    @Test
    fun `while storage is unreadable it stays silent instead of reporting every block as free`() = runTest {
        val silent = object : BlockStateRepository {
            override fun observeStates(): Flow<Map<String, BlockState>> = emptyFlow()
            override suspend fun <T> update(
                transform: (current: Map<String, BlockState>) -> BlockStateUpdate<T>,
            ): T = error("not used")
        }
        val emissions = collect(silent)
        advanceUntilIdle()

        assertEquals(emptyList<Map<String, BlockListStatus>>(), emissions)
    }

    @Test
    fun `a state emitted after the collection started is picked up and nothing is emitted before it ends`() = runTest {
        val states = MutableSharedFlow<Map<String, BlockState>>(replay = 1)
        val repository = object : BlockStateRepository {
            override fun observeStates(): Flow<Map<String, BlockState>> = states
            override suspend fun <T> update(
                transform: (current: Map<String, BlockState>) -> BlockStateUpdate<T>,
            ): T = error("not used")
        }
        val emissions = collect(repository)
        val until = startMillis + 10 * minute
        states.emit(mapOf("B1" to state(cooldownEnd = until)))
        runCurrent()
        advanceTimeBy(9 * minute)
        runCurrent()

        assertEquals(listOf(mapOf("B1" to BlockListStatus.CoolingDown(until))), emissions)
    }
}

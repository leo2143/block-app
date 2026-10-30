package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockRepository
import com.leo.blockapp.domain.fakes.FakeBlockStateRepository
import com.leo.blockapp.domain.fakes.FakeClock
import com.leo.blockapp.domain.fakes.FakeUsageEventSource
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockScreenState
import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.model.ForegroundAppVerdict
import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.model.UsageEventType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetAppBlockingStatusUseCaseTest {

    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val startOfToday = 10 * day
    private val now = startOfToday + 12 * 60 * minute
    private val clock = FakeClock(nowMillis = now)
    private val instagram = "com.instagram.android"

    private val rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60)

    private fun block(id: String, name: String, vararg packageNames: String) = Block(
        id = id,
        name = name,
        packageNames = linkedSetOf(*packageNames),
        rule = rule,
        position = id.removePrefix("B").toLong(),
    )

    private fun cooldownState(untilMillis: Long, vararg packageNames: String) =
        BlockState.initial("Stored", startOfToday).copy(
            cooldownEndMillis = untilMillis,
            cyclesToday = 1,
            holdUntilMillis = untilMillis,
            holdReason = BlockReason.COOLDOWN,
            holdPackageNames = setOf(*packageNames),
        )

    @Test
    fun `reads blocks and states and answers with the remaining time at the current clock`() = runTest {
        val until = now + 35 * minute
        val useCase = GetAppBlockingStatusUseCase(
            FakeBlockRepository(listOf(block("B1", "Social", instagram))),
            FakeBlockStateRepository(mapOf("B1" to cooldownState(until, instagram))),
            clock,
        )

        assertEquals(
            BlockScreenState.Active("Social", until, 35 * minute, BlockReason.COOLDOWN),
            useCase(instagram),
        )
    }

    @Test
    fun `the remaining time follows the clock between queries and turns inactive when the hold ends`() = runTest {
        val until = now + 2 * minute
        val useCase = GetAppBlockingStatusUseCase(
            FakeBlockRepository(listOf(block("B1", "Social", instagram))),
            FakeBlockStateRepository(mapOf("B1" to cooldownState(until, instagram))),
            clock,
        )

        clock.nowMillis = now + minute
        assertEquals(BlockScreenState.Active("Social", until, minute, BlockReason.COOLDOWN), useCase(instagram))

        clock.nowMillis = until
        assertEquals(BlockScreenState.Inactive, useCase(instagram))
    }

    @Test
    fun `a package without state is inactive`() = runTest {
        val useCase = GetAppBlockingStatusUseCase(
            FakeBlockRepository(listOf(block("B1", "Social", instagram))),
            FakeBlockStateRepository(),
            clock,
        )

        assertEquals(BlockScreenState.Inactive, useCase(instagram))
    }

    @Test
    fun `it is read only and never writes the state or the config`() = runTest {
        val blockRepository = FakeBlockRepository(listOf(block("B1", "Social", instagram)))
        val stateRepository = FakeBlockStateRepository(mapOf("B1" to cooldownState(now + minute, instagram)))
        val useCase = GetAppBlockingStatusUseCase(blockRepository, stateRepository, clock)

        useCase(instagram)

        assertTrue(stateRepository.writes.isEmpty())
        assertTrue(blockRepository.writes.isEmpty())
    }

    @Test
    fun `a deleted block shows the stored name`() = runTest {
        val useCase = GetAppBlockingStatusUseCase(
            FakeBlockRepository(),
            FakeBlockStateRepository(mapOf("B1" to cooldownState(now + minute, instagram))),
            clock,
        )

        val status = useCase(instagram) as BlockScreenState.Active

        assertEquals("Stored", status.blockName)
    }

    @Test
    fun `parity a Restricted verdict yields the same until and reason on the block screen`() = runTest {
        val limitRule = rule.copy(usageLimitMinutes = 10)
        val blocks = listOf(block("B1", "Social", instagram).copy(rule = limitRule))
        val blockRepository = FakeBlockRepository(blocks)
        val stateRepository = FakeBlockStateRepository()
        val usage = FakeUsageEventSource(
            listOf(UsageEvent(instagram, "Main", UsageEventType.MOVED_TO_FOREGROUND, now - 15 * minute)),
        )
        val verdict = EvaluateForegroundAppUseCase(blockRepository, stateRepository, usage, clock)(instagram, now)
        val restricted = verdict as ForegroundAppVerdict.Restricted

        val status = GetAppBlockingStatusUseCase(blockRepository, stateRepository, clock)(instagram)

        assertEquals(
            BlockScreenState.Active("Social", restricted.untilMillis, restricted.untilMillis - now, restricted.reason),
            status,
        )
    }
}

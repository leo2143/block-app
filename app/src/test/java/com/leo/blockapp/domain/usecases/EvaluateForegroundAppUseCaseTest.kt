package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockRepository
import com.leo.blockapp.domain.fakes.FakeBlockStateRepository
import com.leo.blockapp.domain.fakes.FakeClock
import com.leo.blockapp.domain.fakes.FakeIdGenerator
import com.leo.blockapp.domain.fakes.FakeInstalledAppCatalog
import com.leo.blockapp.domain.fakes.FakeUsageEventSource
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.model.ForegroundAppVerdict
import com.leo.blockapp.domain.model.InstalledApp
import com.leo.blockapp.domain.model.SaveBlockResult
import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.model.UsageEventType
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class EvaluateForegroundAppUseCaseTest {

    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val startOfToday = 10 * day
    private val startOfNextDay = startOfToday + day
    private val now = startOfToday + 12 * 60 * minute

    private val instagram = "com.instagram.android"
    private val x = "com.twitter.android"
    private val tiktok = "com.tiktok"
    private val whatsapp = "com.whatsapp"

    private val clock = FakeClock(nowMillis = now)
    private val defaultRule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60)
    private val cappedRule = defaultRule.copy(dailyCycleCap = 2)

    private fun block(
        id: String,
        vararg packageNames: String,
        rule: BlockRule = defaultRule,
        enabled: Boolean = true,
        joinedAt: Map<String, Long> = emptyMap(),
    ) = Block(
        id = id,
        name = "Social $id",
        packageNames = linkedSetOf(*packageNames),
        rule = rule,
        position = id.removePrefix("B").toLong(),
        enabled = enabled,
        joinedAtMillisByPackage = joinedAt,
    )

    private fun initialState(blockId: String) = BlockState.initial("Social $blockId", startOfToday)

    private fun foregroundSince(packageName: String, minutesAgo: Long) =
        UsageEvent(packageName, "Main", UsageEventType.MOVED_TO_FOREGROUND, now - minutesAgo * minute)

    private fun backgroundSince(packageName: String, minutesAgo: Long) =
        UsageEvent(packageName, "Main", UsageEventType.MOVED_TO_BACKGROUND, now - minutesAgo * minute)

    private fun cooldownState(blockId: String, cooldownEndMillis: Long, vararg packageNames: String) =
        initialState(blockId).copy(
            cooldownEndMillis = cooldownEndMillis,
            cyclesToday = 1,
            holdUntilMillis = cooldownEndMillis,
            holdReason = BlockReason.COOLDOWN,
            holdPackageNames = setOf(*packageNames),
        )

    private fun capHoldState(blockId: String, cyclesToday: Int, vararg packageNames: String) =
        initialState(blockId).copy(
            cyclesToday = cyclesToday,
            holdUntilMillis = startOfNextDay,
            holdReason = BlockReason.DAILY_CAP,
            holdPackageNames = setOf(*packageNames),
        )

    private inner class Harness(
        blocks: List<Block>,
        states: Map<String, BlockState> = emptyMap(),
        events: List<UsageEvent> = emptyList(),
        beforeApply: suspend () -> Unit = {},
    ) {
        val blockRepository = FakeBlockRepository(blocks)
        val stateRepository = FakeBlockStateRepository(states, beforeApply)
        val usage = FakeUsageEventSource(events)
        val evaluateForegroundApp = EvaluateForegroundAppUseCase(blockRepository, stateRepository, usage, clock)

        suspend fun evaluate(packageName: String, foregroundSinceMillis: Long = now) =
            evaluateForegroundApp(packageName, foregroundSinceMillis)

        suspend fun storedStates() = stateRepository.observeStates().first()
    }

    // --- S15 / E2: not enforced ---

    @Test
    fun `S15 a package in no enabled block and no hold is not enforced and nothing is written or queried`() = runTest {
        val staleOrphan = initialState("G1").copy(cyclesDayStartMillis = startOfToday - day)
        val harness = Harness(
            blocks = listOf(block("B1", instagram)),
            states = mapOf("G1" to staleOrphan),
            events = listOf(foregroundSince(whatsapp, 30)),
        )

        val verdict = harness.evaluate(whatsapp)

        assertEquals(ForegroundAppVerdict.NotEnforced, verdict)
        assertTrue(harness.stateRepository.writes.isEmpty())
        assertTrue(harness.usage.requests.isEmpty())
        assertEquals(mapOf("G1" to staleOrphan), harness.storedStates())
    }

    @Test
    fun `a package of a paused block with no hold is not enforced`() = runTest {
        val harness = Harness(blocks = listOf(block("B1", instagram, enabled = false)))

        assertEquals(ForegroundAppVerdict.NotEnforced, harness.evaluate(instagram))
        assertTrue(harness.stateRepository.writes.isEmpty())
    }

    // --- evaluation and persistence ---

    @Test
    fun `S1 an app below the limit is allowed with what is left and an unchanged state is not written`() = runTest {
        val harness = Harness(blocks = listOf(block("B1", instagram)), events = listOf(foregroundSince(instagram, 4)))

        val verdict = harness.evaluate(instagram)

        assertEquals(ForegroundAppVerdict.Allowed("B1", 6 * minute), verdict)
        assertTrue(harness.stateRepository.writes.isEmpty())
        assertTrue(harness.storedStates().isEmpty())
    }

    @Test
    fun `S2 reaching the limit starts a cooldown persisted in one atomic write`() = runTest {
        val harness = Harness(blocks = listOf(block("B1", instagram)), events = listOf(foregroundSince(instagram, 10)))

        val verdict = harness.evaluate(instagram)

        val cooldownEnd = now + 60 * minute
        assertEquals(ForegroundAppVerdict.Restricted("B1", cooldownEnd, BlockReason.COOLDOWN), verdict)
        assertEquals(1, harness.stateRepository.writes.size)
        assertEquals(
            mapOf("B1" to cooldownState("B1", cooldownEnd, instagram)),
            harness.storedStates(),
        )
    }

    @Test
    fun `S4 a running cooldown keeps the app restricted without another write`() = runTest {
        val harness = Harness(blocks = listOf(block("B1", instagram)), events = listOf(foregroundSince(instagram, 10)))
        harness.evaluate(instagram)

        clock.nowMillis = now + 25 * minute
        val verdict = harness.evaluate(instagram)

        assertEquals(
            ForegroundAppVerdict.Restricted("B1", now + 60 * minute, BlockReason.COOLDOWN),
            verdict,
        )
        assertEquals(1, harness.stateRepository.writes.size)
    }

    @Test
    fun `a state that changed only by the new day is persisted even when the app is allowed`() = runTest {
        val yesterday = startOfToday - day
        val harness = Harness(
            blocks = listOf(block("B1", instagram)),
            states = mapOf(
                "B1" to initialState("B1").copy(
                    cycleStartMillis = yesterday,
                    cyclesToday = 1,
                    cyclesDayStartMillis = yesterday,
                ),
            ),
        )

        val verdict = harness.evaluate(instagram)

        assertEquals(ForegroundAppVerdict.Allowed("B1", 10 * minute), verdict)
        assertEquals(1, harness.stateRepository.writes.size)
        assertEquals(mapOf("B1" to initialState("B1")), harness.storedStates())
    }

    @Test
    fun `usage is measured across the packages of the block`() = runTest {
        val harness = Harness(
            blocks = listOf(block("B1", instagram, x)),
            events = listOf(
                foregroundSince(instagram, 12),
                backgroundSince(instagram, 7),
                foregroundSince(x, 3),
            ),
        )

        assertEquals(ForegroundAppVerdict.Allowed("B1", 2 * minute), harness.evaluate(x))
    }

    // --- S20 / C2 ---

    @Test
    fun `S20 an app moved into a block counts only from the save time`() = runTest {
        val catalog = FakeInstalledAppCatalog(listOf(InstalledApp(instagram, "Instagram"), InstalledApp(x, "X")))
        val harness = Harness(
            blocks = listOf(block("B1", instagram, x), block("B2", tiktok)),
            events = listOf(foregroundSince(instagram, 12)),
        )
        clock.nowMillis = now - 4 * minute
        val saved = SaveBlockUseCase(harness.blockRepository, FakeIdGenerator(), catalog, clock)(
            BlockDraft("B2", "Social B2", linkedSetOf(tiktok, instagram), defaultRule),
            confirmedMovePackages = setOf(instagram),
        )
        assertTrue(saved is SaveBlockResult.Saved)
        clock.nowMillis = now

        val verdict = harness.evaluate(instagram)

        // 12 minutes of Instagram would block it, but only the 4 minutes since the move count in B2.
        assertEquals(ForegroundAppVerdict.Allowed("B2", 6 * minute), verdict)
    }

    // --- X2 ---

    @Test
    fun `X2 a cooldown that ended while the block was paused counts the paused time and may block at once`() = runTest {
        val cooldownEnd = now - 30 * minute
        val harness = Harness(
            blocks = listOf(block("B1", instagram, enabled = true)),
            states = mapOf("B1" to cooldownState("B1", cooldownEnd, instagram)),
            events = listOf(foregroundSince(instagram, 25)),
        )

        val verdict = harness.evaluate(instagram)

        assertEquals(
            ForegroundAppVerdict.Restricted("B1", now + 60 * minute, BlockReason.COOLDOWN),
            verdict,
        )
        assertEquals(2, harness.storedStates().getValue("B1").cyclesToday)
    }

    // --- X6 and the hold rules (E5) ---

    @Test
    fun `X6 an app added during a cap hold is held by the evaluator and stays blocked after a cap raise and a pause`() =
        runTest {
            val harness = Harness(
                blocks = listOf(block("B1", instagram, x, rule = cappedRule)),
                states = mapOf("B1" to capHoldState("B1", cyclesToday = 2, instagram)),
            )

            val first = harness.evaluate(x)

            assertEquals(ForegroundAppVerdict.Restricted("B1", startOfNextDay, BlockReason.DAILY_CAP), first)
            assertEquals(setOf(instagram, x), harness.storedStates().getValue("B1").holdPackageNames)

            harness.blockRepository.update {
                BlockUpdate(
                    BlockChanges(upserts = listOf(block("B1", instagram, x, rule = defaultRule, enabled = false))),
                    Unit,
                )
            }
            val afterEdit = harness.evaluate(x)

            assertEquals(ForegroundAppVerdict.Restricted("B1", startOfNextDay, BlockReason.DAILY_CAP), afterEdit)
            assertEquals(1, harness.stateRepository.writes.size)
        }

    @Test
    fun `pause keeps a running cooldown enforced`() = runTest {
        val cooldownEnd = now + 20 * minute
        val harness = Harness(
            blocks = listOf(block("B1", instagram, enabled = false)),
            states = mapOf("B1" to cooldownState("B1", cooldownEnd, instagram)),
        )

        assertEquals(
            ForegroundAppVerdict.Restricted("B1", cooldownEnd, BlockReason.COOLDOWN),
            harness.evaluate(instagram),
        )
        assertTrue(harness.stateRepository.writes.isEmpty())
    }

    @Test
    fun `a deleted block keeps its held apps restricted and its state with the stored name`() = runTest {
        val harness = Harness(
            blocks = listOf(block("B1", instagram)),
            states = mapOf("B1" to capHoldState("B1", cyclesToday = 2, instagram)),
        )
        DeleteBlockUseCase(harness.blockRepository)("B1")

        val verdict = harness.evaluate(instagram)

        assertEquals(ForegroundAppVerdict.Restricted("B1", startOfNextDay, BlockReason.DAILY_CAP), verdict)
        assertEquals("Social B1", harness.storedStates().getValue("B1").blockName)
        assertTrue(harness.stateRepository.writes.isEmpty())
    }

    @Test
    fun `undo after a delete finds the state intact and still enforces the hold`() = runTest {
        val original = block("B1", instagram, rule = cappedRule)
        val heldState = capHoldState("B1", cyclesToday = 2, instagram)
        val harness = Harness(blocks = listOf(original), states = mapOf("B1" to heldState))
        val deleted = DeleteBlockUseCase(harness.blockRepository)("B1")!!

        val whileDeleted = harness.evaluate(instagram)
        harness.blockRepository.update { BlockUpdate(BlockChanges(upserts = listOf(deleted.block)), Unit) }
        val afterUndo = harness.evaluate(instagram)

        val expected = ForegroundAppVerdict.Restricted("B1", startOfNextDay, BlockReason.DAILY_CAP)
        assertEquals(expected, whileDeleted)
        assertEquals(expected, afterUndo)
        assertEquals(mapOf("B1" to heldState), harness.storedStates())
        assertTrue(harness.stateRepository.writes.isEmpty())
    }

    @Test
    fun `a moved app stays blocked by the hold of its old block until it ends, then the new block rules apply`() =
        runTest {
            val harness = Harness(
                blocks = listOf(block("B1", x), block("B2", instagram)),
                states = mapOf("B1" to capHoldState("B1", cyclesToday = 2, instagram, x)),
            )

            val whileHeld = harness.evaluate(instagram)
            clock.nowMillis = startOfNextDay
            val afterHold = harness.evaluate(instagram)

            assertEquals(ForegroundAppVerdict.Restricted("B1", startOfNextDay, BlockReason.DAILY_CAP), whileHeld)
            assertEquals(ForegroundAppVerdict.Allowed("B2", 10 * minute), afterHold)
        }

    @Test
    fun `a hold that already ended is not carried by a paused block`() = runTest {
        val harness = Harness(
            blocks = listOf(block("B1", instagram, enabled = false)),
            states = mapOf("B1" to cooldownState("B1", now - 1, instagram)),
        )

        assertEquals(ForegroundAppVerdict.NotEnforced, harness.evaluate(instagram))
        assertTrue(harness.stateRepository.writes.isEmpty())
    }

    // --- HoldPolicy first: the evaluator alone is fail-open on a live hold that is not DAILY_CAP ---

    @Test
    fun `a live COOLDOWN-reason hold with no running cooldown still blocks through the use case`() = runTest {
        val until = now + 30 * minute
        val harness = Harness(
            blocks = listOf(block("B1", instagram)),
            states = mapOf(
                "B1" to initialState("B1").copy(
                    holdUntilMillis = until,
                    holdReason = BlockReason.COOLDOWN,
                    holdPackageNames = setOf(instagram),
                ),
            ),
        )

        assertEquals(
            ForegroundAppVerdict.Restricted("B1", until, BlockReason.COOLDOWN),
            harness.evaluate(instagram),
        )
    }

    @Test
    fun `a live hold with no reason blocks as a daily cap through the use case`() = runTest {
        val until = now + 30 * minute
        val harness = Harness(
            blocks = listOf(block("B1", instagram)),
            states = mapOf(
                "B1" to initialState("B1").copy(holdUntilMillis = until, holdPackageNames = setOf(instagram)),
            ),
        )

        assertEquals(
            ForegroundAppVerdict.Restricted("B1", until, BlockReason.DAILY_CAP),
            harness.evaluate(instagram),
        )
    }

    @Test
    fun `an app added to a block under such a hold is blocked too and joins the hold`() = runTest {
        val until = now + 30 * minute
        val harness = Harness(
            blocks = listOf(block("B1", instagram, x)),
            states = mapOf(
                "B1" to initialState("B1").copy(
                    holdUntilMillis = until,
                    holdReason = BlockReason.COOLDOWN,
                    holdPackageNames = setOf(instagram),
                ),
            ),
        )

        val verdict = harness.evaluate(x)

        assertEquals(ForegroundAppVerdict.Restricted("B1", until, BlockReason.COOLDOWN), verdict)
        assertEquals(setOf(instagram, x), harness.storedStates().getValue("B1").holdPackageNames)
    }

    @Test
    fun `overlapping holds on one package restrict by the greatest until`() = runTest {
        val harness = Harness(
            blocks = listOf(block("B2", instagram)),
            states = mapOf(
                "B1" to cooldownState("B1", now + 10 * minute, instagram),
                "B3" to capHoldState("B3", cyclesToday = 2, instagram),
            ),
        )

        assertEquals(
            ForegroundAppVerdict.Restricted("B3", startOfNextDay, BlockReason.DAILY_CAP),
            harness.evaluate(instagram),
        )
    }

    // --- P4: garbage collection ---

    @Test
    fun `P4 an evaluation drops only orphan states that are idle and from a previous day`() = runTest {
        val yesterday = startOfToday - day
        val harness = Harness(
            blocks = listOf(block("B1", instagram)),
            states = mapOf(
                "G1" to initialState("G1").copy(cyclesDayStartMillis = yesterday),
                "G2" to capHoldState("G2", cyclesToday = 2, whatsapp).copy(cyclesDayStartMillis = yesterday),
                "G3" to initialState("G3"),
                "G4" to initialState("G4").copy(
                    cyclesDayStartMillis = yesterday,
                    cooldownEndMillis = now + 10 * minute,
                ),
            ),
        )

        val verdict = harness.evaluate(instagram)

        assertEquals(ForegroundAppVerdict.Allowed("B1", 10 * minute), verdict)
        assertEquals(setOf("G2", "G3", "G4"), harness.storedStates().keys)
        assertEquals(1, harness.stateRepository.writes.size)
    }

    @Test
    fun `a state of an existing paused block is never garbage collected`() = runTest {
        val yesterday = startOfToday - day
        val harness = Harness(
            blocks = listOf(block("B1", instagram), block("B2", x, enabled = false)),
            states = mapOf("B2" to initialState("B2").copy(cyclesDayStartMillis = yesterday)),
        )

        harness.evaluate(instagram)

        assertTrue("B2" in harness.storedStates())
    }

    // --- config re-read and query window ---

    private class SwitchingBlockRepository(private val snapshots: List<List<Block>>) : BlockRepository {
        var reads = 0
            private set

        private fun nextSnapshot() = snapshots[minOf(reads++, snapshots.lastIndex)]

        override fun observeBlocks(): Flow<List<Block>> = flowOf(snapshots.last())
        override suspend fun getBlocks(): List<Block> = nextSnapshot()
        override suspend fun <T> update(transform: (current: List<Block>) -> BlockUpdate<T>): T =
            transform(nextSnapshot()).result
    }

    @Test
    fun `the config is read once per tick`() = runTest {
        val blockRepository = SwitchingBlockRepository(listOf(listOf(block("B1", instagram))))
        val stateRepository = FakeBlockStateRepository()
        val usage = FakeUsageEventSource(listOf(foregroundSince(instagram, 4)))

        val verdict = EvaluateForegroundAppUseCase(blockRepository, stateRepository, usage, clock)(instagram, now)

        assertEquals(ForegroundAppVerdict.Allowed("B1", 6 * minute), verdict)
        assertEquals(1, blockRepository.reads)
    }

    @Test
    fun `an active hold answers without querying usage`() = runTest {
        val harness = Harness(
            blocks = listOf(block("B1", instagram)),
            states = mapOf("B1" to cooldownState("B1", now + 20 * minute, instagram)),
            events = listOf(foregroundSince(instagram, 30)),
        )

        val verdict = harness.evaluate(instagram)

        assertEquals(ForegroundAppVerdict.Restricted("B1", now + 20 * minute, BlockReason.COOLDOWN), verdict)
        assertTrue(harness.usage.requests.isEmpty())
        assertTrue(harness.stateRepository.writes.isEmpty())
    }

    @Test
    fun `without a hold exactly one usage query is made`() = runTest {
        val harness = Harness(blocks = listOf(block("B1", instagram)), events = listOf(foregroundSince(instagram, 4)))

        harness.evaluate(instagram)

        assertEquals(1, harness.usage.requests.size)
    }

    @Test
    fun `an app in the foreground since before midnight with no events in range counts from midnight`() = runTest {
        clock.nowMillis = startOfToday + 30 * minute
        val harness = Harness(blocks = listOf(block("B1", instagram)))

        val verdict = harness.evaluate(instagram, foregroundSinceMillis = startOfToday - 2 * minute)

        assertEquals(
            ForegroundAppVerdict.Restricted("B1", startOfToday + 90 * minute, BlockReason.COOLDOWN),
            verdict,
        )
    }

    @Test
    fun `another package with a stale foreground before the range and no background contributes no usage`() = runTest {
        val harness = Harness(
            blocks = listOf(block("B1", instagram, x)),
            events = listOf(
                UsageEvent(x, "Main", UsageEventType.MOVED_TO_FOREGROUND, startOfToday - 2 * minute),
                foregroundSince(instagram, 4),
            ),
        )

        val verdict = harness.evaluate(instagram, foregroundSinceMillis = now - 4 * minute)

        assertEquals(ForegroundAppVerdict.Allowed("B1", 6 * minute), verdict)
    }

    @Test
    fun `an app just opened whose foreground event is not visible yet adds no usage`() = runTest {
        val harness = Harness(blocks = listOf(block("B1", instagram)))

        val verdict = harness.evaluate(instagram, foregroundSinceMillis = now)

        assertEquals(ForegroundAppVerdict.Allowed("B1", 10 * minute), verdict)
    }

    @Test
    fun `an app in the foreground since before the inactivity lookback counts from the start of the window`() = runTest {
        val rule = defaultRule.copy(inactivityResetMinutes = 60)
        val harness = Harness(blocks = listOf(block("B1", instagram, rule = rule)))

        val verdict = harness.evaluate(instagram, foregroundSinceMillis = now - (12 * 60 + 90) * minute)

        assertEquals(
            ForegroundAppVerdict.Restricted("B1", now + 60 * minute, BlockReason.COOLDOWN),
            verdict,
        )
    }

    @Test
    fun `the query window starts at the start of the day and ends now`() = runTest {
        val harness = Harness(blocks = listOf(block("B1", instagram)))

        harness.evaluate(instagram)

        assertEquals(listOf(startOfToday to now), harness.usage.requests)
    }

    @Test
    fun `with an inactivity window the query reaches back by it`() = runTest {
        val rule = defaultRule.copy(inactivityResetMinutes = 60)
        val harness = Harness(blocks = listOf(block("B1", instagram, rule = rule)))

        harness.evaluate(instagram)

        assertEquals(listOf((startOfToday - 60 * minute) to now), harness.usage.requests)
    }

    @Test
    fun `a long inactivity window is bounded to one day before the start of the day`() = runTest {
        val rule = defaultRule.copy(inactivityResetMinutes = 50 * 60)
        val harness = Harness(blocks = listOf(block("B1", instagram, rule = rule)))

        harness.evaluate(instagram)

        val (from, to) = harness.usage.requests.single()
        assertEquals(startOfToday - day, from)
        assertTrue(to - from <= 2 * day)
    }

    @Test
    fun `a short gap across midnight is seen thanks to the lookback and keeps the cycle running`() = runTest {
        val rule = defaultRule.copy(inactivityResetMinutes = 60)
        val harness = Harness(
            blocks = listOf(block("B1", instagram, rule = rule)),
            events = listOf(
                foregroundSince(instagram, 12 * 60 + 30),
                backgroundSince(instagram, 12 * 60 + 5),
                foregroundSince(instagram, 11 * 60 + 50),
            ),
        )

        // Exit 5 minutes before midnight, back 10 minutes after: a 15 minute gap, so no restart and the whole
        // open session since midnight counts. Without the lookback the exit would be invisible and restart the cycle.
        assertEquals(
            ForegroundAppVerdict.Restricted("B1", now + 60 * minute, BlockReason.COOLDOWN),
            harness.evaluate(instagram),
        )
    }

    // --- race and failures ---

    @Test
    fun `two racing evaluations start the cooldown once`() = runTest {
        val harness = Harness(
            blocks = listOf(block("B1", instagram)),
            events = listOf(foregroundSince(instagram, 10)),
            beforeApply = { yield() },
        )

        val verdicts = listOf(async { harness.evaluate(instagram) }, async { harness.evaluate(instagram) }).awaitAll()

        val expected = ForegroundAppVerdict.Restricted("B1", now + 60 * minute, BlockReason.COOLDOWN)
        assertEquals(listOf(expected, expected), verdicts)
        assertEquals(1, harness.storedStates().getValue("B1").cyclesToday)
        assertEquals(1, harness.stateRepository.writes.size)
        assertEquals(1, harness.stateRepository.maxConcurrentUpdates)
    }

    @Test
    fun `a failing state write propagates so the caller can fail closed`() = runTest {
        val harness = Harness(blocks = listOf(block("B1", instagram)), events = listOf(foregroundSince(instagram, 10)))
        harness.stateRepository.writeFailure = IOException("disk full")

        try {
            harness.evaluate(instagram)
            fail("expected an IOException")
        } catch (expected: IOException) {
            assertEquals("disk full", expected.message)
        }
    }

    @Test
    fun `a failing usage query propagates`() = runTest {
        val harness = Harness(blocks = listOf(block("B1", instagram)))
        harness.usage.failure = IOException("no usage")

        try {
            harness.evaluate(instagram)
            fail("expected an IOException")
        } catch (expected: IOException) {
            assertEquals("no usage", expected.message)
        }
    }
}

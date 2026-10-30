package com.leo.blockapp.domain.rules

import com.leo.blockapp.domain.model.BlockDecision
import com.leo.blockapp.domain.model.BlockEvaluation
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockReason.COOLDOWN
import com.leo.blockapp.domain.model.BlockReason.DAILY_CAP
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.usage.ForegroundSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec cases S1-S19 and X1-X8 against the pure evaluator. Limit 10 min, cooldown 60 min unless stated. */
class BlockEvaluatorTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // Day 100 of the fake calendar: midnight is startOfDay, "tomorrow" starts at startOfNextDay.
    private val startOfDay = 100 * day
    private val startOfNextDay = startOfDay + day
    private val startOfYesterday = startOfDay - day

    private val instagram = "com.instagram.android"
    private val x = "com.twitter.android"
    private val blockName = "Social"

    private fun at(hours: Int, minutes: Int = 0) = startOfDay + hours * hour + minutes * minute

    private fun rule(
        limitMinutes: Int = 10,
        cooldownMinutes: Int = 60,
        dailyCap: Int? = null,
        inactivityMinutes: Int? = null,
    ) = BlockRule.AccumulatedUsageWithCooldown(limitMinutes, cooldownMinutes, dailyCap, inactivityMinutes)

    private fun state(
        cycleStartMillis: Long = startOfDay,
        cooldownEndMillis: Long? = null,
        cyclesToday: Int = 0,
        cyclesDayStartMillis: Long = startOfDay,
        holdUntilMillis: Long? = null,
        holdReason: BlockReason? = null,
        holdPackageNames: Set<String> = emptySet(),
        name: String = blockName,
    ) = BlockState(
        cycleStartMillis = cycleStartMillis,
        cooldownEndMillis = cooldownEndMillis,
        cyclesToday = cyclesToday,
        cyclesDayStartMillis = cyclesDayStartMillis,
        holdUntilMillis = holdUntilMillis,
        holdReason = holdReason,
        holdPackageNames = holdPackageNames,
        blockName = name,
    )

    private fun evaluate(
        rule: BlockRule = rule(),
        state: BlockState = state(),
        now: Long,
        sessions: List<ForegroundSession> = emptyList(),
        packages: Set<String> = setOf(instagram),
        joinedAt: Map<String, Long> = emptyMap(),
    ): BlockEvaluation = BlockEvaluator.evaluate(
        rule = rule,
        blockName = blockName,
        packageNames = packages,
        joinedAtMillisByPackage = joinedAt,
        state = state,
        sessions = sessions,
        nowMillis = now,
        startOfDayMillis = startOfDay,
        startOfNextDayMillis = startOfNextDay,
    )

    // ---- Steps 1-2: new day and cooldown (task 3.1) ----

    @Test
    fun `S4 a running cooldown stays blocked and its fields are untouched`() {
        val running = state(
            cycleStartMillis = at(9),
            cooldownEndMillis = at(11),
            cyclesToday = 1,
            holdUntilMillis = at(11),
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )

        val result = evaluate(state = running, now = at(10, 25))

        assertEquals(BlockDecision.Blocked(at(11), COOLDOWN), result.decision)
        assertEquals(running, result.state)
    }

    @Test
    fun `S5 a cooldown that ended two minutes ago restarts the cycle at its end`() {
        val ended = state(
            cycleStartMillis = at(9),
            cooldownEndMillis = at(10, 58),
            cyclesToday = 1,
            holdUntilMillis = at(10, 58),
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )
        // Usage before the cooldown ended must not count towards the new cycle.
        val before = ForegroundSession(instagram, at(10, 40), at(10, 50))

        val result = evaluate(state = ended, now = at(11), sessions = listOf(before))

        assertEquals(BlockDecision.Allow(10 * minute), result.decision)
        assertEquals(at(10, 58), result.state.cycleStartMillis)
        assertNull(result.state.cooldownEndMillis)
        assertEquals(1, result.state.cyclesToday)
        assertNull(result.state.holdUntilMillis)
        assertNull(result.state.holdReason)
        assertEquals(emptySet<String>(), result.state.holdPackageNames)
    }

    @Test
    fun `S6 a state reloaded after a reboot keeps blocking for the 40 minutes left`() {
        val reloaded = state(
            cycleStartMillis = at(9),
            cooldownEndMillis = at(12),
            cyclesToday = 1,
            holdUntilMillis = at(12),
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )

        val result = evaluate(state = reloaded, now = at(11, 20))

        assertEquals(BlockDecision.Blocked(at(12), COOLDOWN), result.decision)
        assertEquals(40 * minute, (result.decision as BlockDecision.Blocked).untilMillis - at(11, 20))
    }

    @Test
    fun `S17 a cooldown crossing midnight blocks until it ends and resets the counter once`() {
        val crossing = state(
            cycleStartMillis = startOfYesterday + 23 * hour,
            cooldownEndMillis = at(0, 30),
            cyclesToday = 1,
            cyclesDayStartMillis = startOfYesterday,
            holdUntilMillis = at(0, 30),
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )

        val during = evaluate(state = crossing, now = at(0, 10))

        assertEquals(BlockDecision.Blocked(at(0, 30), COOLDOWN), during.decision)
        // The new-day normalization is persisted even though the verdict is Blocked.
        assertEquals(0, during.state.cyclesToday)
        assertEquals(startOfDay, during.state.cyclesDayStartMillis)
        assertEquals(at(0, 30), during.state.cooldownEndMillis)

        val after = evaluate(state = during.state, now = at(0, 31))

        assertEquals(BlockDecision.Allow(10 * minute), after.decision)
        assertEquals(0, after.state.cyclesToday)
        assertEquals(at(0, 30), after.state.cycleStartMillis)
        assertNull(after.state.cooldownEndMillis)
    }

    @Test
    fun `X1 a day change during a cooldown persists with the cooldown fields intact`() {
        val crossing = state(
            cycleStartMillis = startOfYesterday + 22 * hour,
            cooldownEndMillis = at(1),
            cyclesToday = 2,
            cyclesDayStartMillis = startOfYesterday,
            holdUntilMillis = at(1),
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )

        val result = evaluate(state = crossing, now = at(0, 5))

        assertEquals(BlockDecision.Blocked(at(1), COOLDOWN), result.decision)
        assertEquals(at(1), result.state.cooldownEndMillis)
        assertEquals(at(1), result.state.holdUntilMillis)
        assertEquals(COOLDOWN, result.state.holdReason)
        assertEquals(0, result.state.cyclesToday)
        assertEquals(startOfDay, result.state.cyclesDayStartMillis)
    }

    @Test
    fun `X1 after a cooldown that ended on the previous day the cycle starts at midnight`() {
        val endedYesterday = state(
            cycleStartMillis = startOfYesterday + 22 * hour,
            cooldownEndMillis = startOfYesterday + 23 * hour,
            cyclesToday = 2,
            cyclesDayStartMillis = startOfYesterday,
            holdUntilMillis = startOfYesterday + 23 * hour,
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )

        val result = evaluate(state = endedYesterday, now = at(0, 10))

        assertEquals(BlockDecision.Allow(10 * minute), result.decision)
        assertEquals(startOfDay, result.state.cycleStartMillis)
        assertNull(result.state.cooldownEndMillis)
        assertEquals(0, result.state.cyclesToday)
        assertNull(result.state.holdUntilMillis)
    }

    @Test
    fun `X3 a third cooldown crossing midnight stays a cooldown with no daily cap hold`() {
        val third = state(
            cycleStartMillis = startOfYesterday + 23 * hour,
            cooldownEndMillis = at(0, 30),
            cyclesToday = 3,
            cyclesDayStartMillis = startOfYesterday,
            holdUntilMillis = at(0, 30),
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )

        val result = evaluate(rule = rule(dailyCap = 3), state = third, now = at(0, 10))

        assertEquals(BlockDecision.Blocked(at(0, 30), COOLDOWN), result.decision)
        assertEquals(0, result.state.cyclesToday)
        assertEquals(COOLDOWN, result.state.holdReason)
        assertEquals(at(0, 30), result.state.holdUntilMillis)
    }

    @Test
    fun `X4 a hold that expired at midnight starts the new day normally`() {
        val capped = state(
            cycleStartMillis = startOfYesterday + 20 * hour,
            cyclesToday = 3,
            cyclesDayStartMillis = startOfYesterday,
            holdUntilMillis = startOfDay,
            holdReason = DAILY_CAP,
            holdPackageNames = setOf(instagram),
        )

        val result = evaluate(rule = rule(dailyCap = 3), state = capped, now = at(0, 1))

        assertEquals(BlockDecision.Allow(10 * minute), result.decision)
        assertEquals(0, result.state.cyclesToday)
        assertEquals(startOfDay, result.state.cycleStartMillis)
        assertNull(result.state.holdUntilMillis)
        assertNull(result.state.holdReason)
        assertEquals(emptySet<String>(), result.state.holdPackageNames)
    }

    @Test
    fun `S17 stale fields are the only change when the day changes with nothing running`() {
        val result = evaluate(
            state = state(cyclesToday = 2, cyclesDayStartMillis = startOfYesterday, cycleStartMillis = startOfYesterday),
            now = at(9),
        )

        assertEquals(0, result.state.cyclesToday)
        assertEquals(startOfDay, result.state.cyclesDayStartMillis)
        assertEquals(startOfDay, result.state.cycleStartMillis)
    }

    // ---- Step 3: daily cap and hold floor (task 3.2) ----

    private fun capHeld(
        cyclesToday: Int = 3,
        cooldownEndMillis: Long? = null,
        packages: Set<String> = setOf(instagram),
        until: Long = startOfNextDay,
    ) = state(
        cycleStartMillis = at(20),
        cooldownEndMillis = cooldownEndMillis,
        cyclesToday = cyclesToday,
        holdUntilMillis = until,
        holdReason = DAILY_CAP,
        holdPackageNames = packages,
    )

    @Test
    fun `S7 no daily cap never blocks however many cycles ran`() {
        val result = evaluate(rule = rule(dailyCap = null), state = state(cyclesToday = 5), now = at(9))

        assertEquals(BlockDecision.Allow(10 * minute), result.decision)
    }

    @Test
    fun `S8 the cap reached with the third cooldown over blocks until midnight`() {
        val third = capHeld(cooldownEndMillis = at(20, 55))

        val result = evaluate(rule = rule(dailyCap = 3), state = third, now = at(21, 5))

        assertEquals(BlockDecision.Blocked(startOfNextDay, DAILY_CAP), result.decision)
        assertEquals(startOfNextDay, result.state.holdUntilMillis)
        assertEquals(DAILY_CAP, result.state.holdReason)
        assertEquals(3, result.state.cyclesToday)
    }

    @Test
    fun `S8 a cap reached with no hold written yet creates the hold until midnight`() {
        val reached = state(cycleStartMillis = at(20), cyclesToday = 3)

        val result = evaluate(rule = rule(dailyCap = 3), state = reached, now = at(21))

        assertEquals(BlockDecision.Blocked(startOfNextDay, DAILY_CAP), result.decision)
        assertEquals(startOfNextDay, result.state.holdUntilMillis)
        assertEquals(DAILY_CAP, result.state.holdReason)
        assertEquals(setOf(instagram), result.state.holdPackageNames)
    }

    @Test
    fun `S9 a reached cap is lifted by midnight`() {
        val yesterdayCap = state(
            cycleStartMillis = startOfYesterday + 20 * hour,
            cyclesToday = 3,
            cyclesDayStartMillis = startOfYesterday,
            holdUntilMillis = startOfDay,
            holdReason = DAILY_CAP,
            holdPackageNames = setOf(instagram),
        )

        val result = evaluate(rule = rule(dailyCap = 3), state = yesterdayCap, now = at(8))

        assertEquals(BlockDecision.Allow(10 * minute), result.decision)
        assertEquals(0, result.state.cyclesToday)
    }

    @Test
    fun `X5 an active daily cap hold stays blocked when the cap is raised`() {
        val result = evaluate(rule = rule(dailyCap = 10), state = capHeld(), now = at(21))

        assertEquals(BlockDecision.Blocked(startOfNextDay, DAILY_CAP), result.decision)
    }

    @Test
    fun `X5 an active daily cap hold stays blocked when the cap is removed`() {
        val result = evaluate(rule = rule(dailyCap = null), state = capHeld(), now = at(21))

        assertEquals(BlockDecision.Blocked(startOfNextDay, DAILY_CAP), result.decision)
        assertEquals(capHeld(), result.state)
    }

    @Test
    fun `X6 packages added to a held block are unioned into the hold`() {
        val result = evaluate(
            rule = rule(dailyCap = 10),
            state = capHeld(packages = setOf(instagram)),
            now = at(21),
            packages = setOf(instagram, x),
        )

        assertEquals(BlockDecision.Blocked(startOfNextDay, DAILY_CAP), result.decision)
        assertEquals(setOf(instagram, x), result.state.holdPackageNames)
    }

    @Test
    fun `X7 a lowered cap with a stale cooldown hold holds until midnight and never in the past`() {
        val stale = state(
            cycleStartMillis = at(8),
            cyclesToday = 3,
            holdUntilMillis = at(9),
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )

        val result = evaluate(rule = rule(dailyCap = 2), state = stale, now = at(15))

        assertEquals(BlockDecision.Blocked(startOfNextDay, DAILY_CAP), result.decision)
        assertEquals(startOfNextDay, result.state.holdUntilMillis)
        assertEquals(DAILY_CAP, result.state.holdReason)
    }

    @Test
    fun `X8 a hold write never shortens a longer active hold`() {
        // Inconsistent on purpose: a live hold reaching past midnight with a cap reached must keep its end.
        val longer = state(
            cycleStartMillis = at(8),
            cyclesToday = 3,
            holdUntilMillis = startOfNextDay + 2 * hour,
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )

        val result = evaluate(rule = rule(dailyCap = 3), state = longer, now = at(15))

        assertEquals(startOfNextDay + 2 * hour, result.state.holdUntilMillis)
        assertEquals(COOLDOWN, result.state.holdReason)
        assertEquals(BlockDecision.Blocked(startOfNextDay + 2 * hour, COOLDOWN), result.decision)
    }

    // ---- Step 4: inactivity reset (task 3.3). Decisions for S10-S12 are asserted with the usage steps. ----

    private fun session(packageName: String, start: Long, end: Long) = ForegroundSession(packageName, start, end)

    @Test
    fun `S11 returning after the inactivity window restarts the cycle at the current session`() {
        val sessions = listOf(
            session(instagram, at(10), at(10, 5)),
            session(instagram, at(12, 5), at(12, 6)),
        )

        val result = evaluate(rule = rule(inactivityMinutes = 60), now = at(12, 6), sessions = sessions)

        assertEquals(at(12, 5), result.state.cycleStartMillis)
    }

    @Test
    fun `S12 returning inside the inactivity window keeps the cycle`() {
        val sessions = listOf(
            session(instagram, at(10), at(10, 5)),
            session(instagram, at(10, 35), at(10, 36)),
        )

        val result = evaluate(rule = rule(inactivityMinutes = 60), now = at(10, 36), sessions = sessions)

        assertEquals(startOfDay, result.state.cycleStartMillis)
    }

    @Test
    fun `S10 without inactivity configured the cycle is never restarted by a long absence`() {
        val sessions = listOf(
            session(instagram, at(10), at(10, 5)),
            session(instagram, at(13, 5), at(13, 6)),
        )

        val result = evaluate(rule = rule(inactivityMinutes = null), now = at(13, 6), sessions = sessions)

        assertEquals(startOfDay, result.state.cycleStartMillis)
    }

    @Test
    fun `a gap of exactly the inactivity window restarts the cycle`() {
        val sessions = listOf(
            session(instagram, at(10), at(10, 5)),
            session(instagram, at(11, 5), at(11, 6)),
        )

        val result = evaluate(rule = rule(inactivityMinutes = 60), now = at(11, 6), sessions = sessions)

        assertEquals(at(11, 5), result.state.cycleStartMillis)
    }

    @Test
    fun `a previous exit older than the lookback bound counts as no previous exit`() {
        // The window is 50 h but the lookback is capped at startOfDay - 24 h, so the exit 30 h before midnight is
        // invisible: no previous exit, inactive, the cycle restarts.
        val sessions = listOf(
            session(instagram, startOfDay - 31 * hour, startOfDay - 30 * hour),
            session(instagram, at(9), at(9, 1)),
        )

        val result = evaluate(rule = rule(inactivityMinutes = 50 * 60), now = at(9, 1), sessions = sessions)

        assertEquals(at(9), result.state.cycleStartMillis)
    }

    @Test
    fun `a previous exit inside the lookback bound with a gap below the window keeps the cycle`() {
        val sessions = listOf(
            session(instagram, startOfDay - 14 * hour, startOfDay - 13 * hour),
            session(instagram, at(9), at(9, 1)),
        )

        val result = evaluate(rule = rule(inactivityMinutes = 50 * 60), now = at(9, 1), sessions = sessions)

        assertEquals(startOfDay, result.state.cycleStartMillis)
    }

    @Test
    fun `no previous exit means inactive and the cycle restarts`() {
        val sessions = listOf(session(instagram, at(9), at(9, 1)))

        val result = evaluate(rule = rule(inactivityMinutes = 60), now = at(9, 1), sessions = sessions)

        assertEquals(at(9), result.state.cycleStartMillis)
    }

    @Test
    fun `adjacent sessions of different packages are one session so the gap is measured from the merged start`() {
        // Instagram 12:00-12:30 then X 12:30-12:31 (open). Merged, the current session starts at 12:00 and the
        // previous exit is 10:05, a 115 min gap. Unmerged, X would see Instagram's exit at 12:30 (no gap).
        val sessions = listOf(
            session(instagram, at(10), at(10, 5)),
            session(instagram, at(12), at(12, 30)),
            session(x, at(12, 30), at(12, 31)),
        )

        val result = evaluate(
            rule = rule(inactivityMinutes = 60),
            now = at(12, 31),
            sessions = sessions,
            packages = setOf(instagram, x),
        )

        assertEquals(at(12), result.state.cycleStartMillis)
    }

    @Test
    fun `sessions of different packages with a short gap keep the cycle`() {
        val sessions = listOf(
            session(instagram, at(12), at(12, 5)),
            session(x, at(12, 15), at(12, 16)),
        )

        val result = evaluate(
            rule = rule(inactivityMinutes = 60),
            now = at(12, 16),
            sessions = sessions,
            packages = setOf(instagram, x),
        )

        assertEquals(startOfDay, result.state.cycleStartMillis)
    }

    @Test
    fun `time spent held counts as inactivity`() {
        // Last exit 10:00, cooldown 10:00-11:00 (over), back at 11:05: a 65 min gap.
        val afterCooldown = state(
            cycleStartMillis = at(9),
            cooldownEndMillis = at(11),
            cyclesToday = 1,
            holdUntilMillis = at(11),
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )
        val sessions = listOf(
            session(instagram, at(9, 50), at(10)),
            session(instagram, at(11, 5), at(11, 6)),
        )

        val result = evaluate(rule = rule(inactivityMinutes = 60), state = afterCooldown, now = at(11, 6), sessions = sessions)

        assertEquals(at(11, 5), result.state.cycleStartMillis)
    }

    @Test
    fun `the inactivity reset only moves the cycle forward`() {
        // A session open since 12:00 across a cooldown that ended at 13:00: the cycle keeps starting at 13:00.
        val afterCooldown = state(cycleStartMillis = at(9), cooldownEndMillis = at(13), cyclesToday = 1)
        val sessions = listOf(session(instagram, at(12), at(13, 1)))

        val result = evaluate(rule = rule(inactivityMinutes = 60), state = afterCooldown, now = at(13, 1), sessions = sessions)

        assertEquals(at(13), result.state.cycleStartMillis)
    }

    @Test
    fun `sessions of packages outside the block are ignored for inactivity`() {
        val sessions = listOf(
            session(instagram, at(10), at(10, 5)),
            session("com.other.app", at(12), at(12, 30)),
            session(instagram, at(12, 31), at(12, 32)),
        )

        val result = evaluate(rule = rule(inactivityMinutes = 60), now = at(12, 32), sessions = sessions)

        assertEquals(at(12, 31), result.state.cycleStartMillis)
    }

    @Test
    fun `without a session open now the app has just opened and the gap runs to now`() {
        val sessions = listOf(session(instagram, at(10), at(10, 5)))

        val returned = evaluate(rule = rule(inactivityMinutes = 60), now = at(14), sessions = sessions)
        val stillActive = evaluate(rule = rule(inactivityMinutes = 60), now = at(10, 30), sessions = sessions)

        assertEquals(at(14), returned.state.cycleStartMillis)
        assertEquals(startOfDay, stillActive.state.cycleStartMillis)
    }

    // ---- Steps 5-7: measure, limit, allow (task 3.4) ----

    @Test
    fun `S1 four minutes used leaves six`() {
        val result = evaluate(now = at(9, 4), sessions = listOf(session(instagram, at(9), at(9, 4))))

        assertEquals(BlockDecision.Allow(6 * minute), result.decision)
        assertEquals(state(), result.state)
    }

    @Test
    fun `S2 ten minutes used starts a cooldown of 60 minutes and counts the cycle`() {
        val result = evaluate(now = at(9, 10), sessions = listOf(session(instagram, at(9), at(9, 10))))

        assertEquals(BlockDecision.Blocked(at(10, 10), COOLDOWN), result.decision)
        assertEquals(at(10, 10), result.state.cooldownEndMillis)
        assertEquals(1, result.state.cyclesToday)
        assertEquals(at(10, 10), result.state.holdUntilMillis)
        assertEquals(COOLDOWN, result.state.holdReason)
        assertEquals(setOf(instagram), result.state.holdPackageNames)
        assertEquals(blockName, result.state.blockName)
    }

    @Test
    fun `S3 usage accumulates across separate visits when inactivity is off`() {
        val sessions = listOf(
            session(instagram, at(9), at(9, 5)),
            session(instagram, at(9, 25), at(9, 30)),
        )

        val result = evaluate(now = at(9, 30), sessions = sessions)

        assertEquals(BlockDecision.Blocked(at(10, 30), COOLDOWN), result.decision)
    }

    @Test
    fun `S10 with inactivity off a return three hours later still counts the earlier minutes`() {
        val result = evaluate(
            rule = rule(inactivityMinutes = null),
            now = at(13, 5),
            sessions = listOf(session(instagram, at(10), at(10, 5))),
        )

        assertEquals(BlockDecision.Allow(5 * minute), result.decision)
    }

    @Test
    fun `S11 returning after the inactivity window starts with no usage`() {
        val result = evaluate(
            rule = rule(inactivityMinutes = 60),
            now = at(12, 5),
            sessions = listOf(session(instagram, at(10), at(10, 5))),
        )

        assertEquals(BlockDecision.Allow(10 * minute), result.decision)
        assertEquals(at(12, 5), result.state.cycleStartMillis)
    }

    @Test
    fun `S12 returning inside the inactivity window keeps the earlier minutes`() {
        val result = evaluate(
            rule = rule(inactivityMinutes = 60),
            now = at(10, 35),
            sessions = listOf(session(instagram, at(10), at(10, 5))),
        )

        assertEquals(BlockDecision.Allow(5 * minute), result.decision)
    }

    @Test
    fun `S13 time in one app of the block is shared with the others`() {
        val result = evaluate(
            now = at(9, 6),
            sessions = listOf(session(instagram, at(9), at(9, 5))),
            packages = setOf(instagram, x),
        )

        assertEquals(BlockDecision.Allow(5 * minute), result.decision)
    }

    @Test
    fun `S14 combined usage reaching the limit blocks the whole block`() {
        val sessions = listOf(
            session(instagram, at(9), at(9, 5)),
            session(x, at(9, 6), at(9, 11)),
        )

        val first = evaluate(now = at(9, 11), sessions = sessions, packages = setOf(instagram, x))
        val afterwards = evaluate(state = first.state, now = at(9, 12), sessions = sessions, packages = setOf(instagram, x))

        assertEquals(BlockDecision.Blocked(at(10, 11), COOLDOWN), first.decision)
        assertEquals(BlockDecision.Blocked(at(10, 11), COOLDOWN), afterwards.decision)
        assertEquals(1, afterwards.state.cyclesToday)
    }

    @Test
    fun `S16 minutes from before midnight do not count and the counter starts at zero`() {
        val lateNight = state(
            cycleStartMillis = startOfYesterday + 23 * hour,
            cyclesToday = 0,
            cyclesDayStartMillis = startOfYesterday,
        )
        // 8 min at 23:50 (an open session clipped at midnight would not reach the evaluator from before startOfDay).
        val sessions = listOf(session(instagram, startOfYesterday + 23 * hour + 50 * minute, startOfDay + 2 * minute))

        val result = evaluate(state = lateNight, now = at(0, 10), sessions = sessions)

        // Only the 2 minutes after midnight count: usage is clipped to the start of the day.
        assertEquals(BlockDecision.Allow(8 * minute), result.decision)
        assertEquals(0, result.state.cyclesToday)
        assertEquals(startOfDay, result.state.cycleStartMillis)
    }

    @Test
    fun `S16 with no usage after midnight the app starts with the whole limit`() {
        val lateNight = state(cycleStartMillis = startOfYesterday + 23 * hour, cyclesDayStartMillis = startOfYesterday)
        val sessions = listOf(session(instagram, startOfYesterday + 23 * hour + 50 * minute, startOfDay - 2 * minute))

        val result = evaluate(state = lateNight, now = at(0, 10), sessions = sessions)

        assertEquals(BlockDecision.Allow(10 * minute), result.decision)
        assertEquals(0, result.state.cyclesToday)
    }

    @Test
    fun `S18 raising the limit mid cycle keeps the minutes already used`() {
        val sessions = listOf(session(instagram, at(9), at(9, 8)))

        val atTen = evaluate(rule = rule(limitMinutes = 10), now = at(9, 8), sessions = sessions)
        val raised = evaluate(rule = rule(limitMinutes = 15), state = atTen.state, now = at(9, 8), sessions = sessions)

        assertEquals(BlockDecision.Allow(2 * minute), atTen.decision)
        assertEquals(BlockDecision.Allow(7 * minute), raised.decision)
    }

    @Test
    fun `S19 a limit change during a running cooldown leaves the cooldown end alone`() {
        val running = state(
            cycleStartMillis = at(9),
            cooldownEndMillis = at(11),
            cyclesToday = 1,
            holdUntilMillis = at(11),
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )

        val result = evaluate(rule = rule(limitMinutes = 30, cooldownMinutes = 5), state = running, now = at(10))

        assertEquals(BlockDecision.Blocked(at(11), COOLDOWN), result.decision)
        assertEquals(running, result.state)

        val next = evaluate(rule = rule(limitMinutes = 30), state = result.state, now = at(11, 1))
        assertEquals(BlockDecision.Allow(30 * minute), next.decision)
    }

    @Test
    fun `usage is counted from the cycle start only`() {
        val restarted = state(cycleStartMillis = at(9, 30))
        val sessions = listOf(session(instagram, at(9), at(9, 35)))

        val result = evaluate(state = restarted, now = at(9, 35), sessions = sessions)

        assertEquals(BlockDecision.Allow(5 * minute), result.decision)
    }

    @Test
    fun `a package that joined the block later only counts from its join time`() {
        val sessions = listOf(
            session(instagram, at(9), at(9, 8)),
            session(x, at(9, 8), at(9, 12)),
        )

        // X joined at 09:10: only its last 2 minutes count, plus Instagram's 8.
        val result = evaluate(
            now = at(9, 12),
            sessions = sessions,
            packages = setOf(instagram, x),
            joinedAt = mapOf(x to at(9, 10)),
        )

        assertEquals(BlockDecision.Blocked(at(10, 12), COOLDOWN), result.decision)
    }

    @Test
    fun `a package that joined the block later does not count its earlier minutes`() {
        val sessions = listOf(
            session(instagram, at(9), at(9, 3)),
            session(x, at(9, 3), at(9, 20)),
        )

        val result = evaluate(
            now = at(9, 20),
            sessions = sessions,
            packages = setOf(instagram, x),
            joinedAt = mapOf(x to at(9, 15)),
        )

        assertEquals(BlockDecision.Allow(2 * minute), result.decision)
    }

    @Test
    fun `usage never goes back before midnight even for an old cycle start`() {
        val old = state(cycleStartMillis = startOfYesterday + 20 * hour, cyclesDayStartMillis = startOfDay)
        val sessions = listOf(session(instagram, startOfDay - 9 * minute, startOfDay + minute))

        val result = evaluate(state = old, now = at(0, 1), sessions = sessions)

        assertEquals(BlockDecision.Allow(9 * minute), result.decision)
    }

    @Test
    fun `sessions of packages outside the block are not measured`() {
        val sessions = listOf(session("com.other.app", at(9), at(9, 30)))

        val result = evaluate(now = at(9, 30), sessions = sessions)

        assertEquals(BlockDecision.Allow(10 * minute), result.decision)
    }

    @Test
    fun `the third cooldown with the cap reached holds until midnight but blocks as a cooldown first`() {
        val second = state(cycleStartMillis = at(20), cyclesToday = 2)

        val result = evaluate(
            rule = rule(dailyCap = 3),
            state = second,
            now = at(20, 10),
            sessions = listOf(session(instagram, at(20), at(20, 10))),
        )

        assertEquals(BlockDecision.Blocked(at(21, 10), COOLDOWN), result.decision)
        assertEquals(3, result.state.cyclesToday)
        assertEquals(at(21, 10), result.state.cooldownEndMillis)
        assertEquals(startOfNextDay, result.state.holdUntilMillis)
        assertEquals(DAILY_CAP, result.state.holdReason)
    }

    @Test
    fun `a cap cooldown that reaches the next day keeps a plain cooldown hold`() {
        val second = state(cycleStartMillis = at(23), cyclesToday = 2)

        val result = evaluate(
            rule = rule(dailyCap = 3),
            state = second,
            now = at(23, 20),
            sessions = listOf(session(instagram, at(23, 10), at(23, 20))),
        )

        assertEquals(BlockDecision.Blocked(at(24, 20), COOLDOWN), result.decision)
        assertEquals(at(24, 20), result.state.holdUntilMillis)
        assertEquals(COOLDOWN, result.state.holdReason)
    }

    @Test
    fun `a cooldown below the cap keeps a cooldown hold`() {
        val result = evaluate(
            rule = rule(dailyCap = 3),
            state = state(cyclesToday = 1),
            now = at(9, 10),
            sessions = listOf(session(instagram, at(9), at(9, 10))),
        )

        assertEquals(COOLDOWN, result.state.holdReason)
        assertEquals(at(10, 10), result.state.holdUntilMillis)
        assertEquals(2, result.state.cyclesToday)
    }

    @Test
    fun `a new cooldown never shortens a longer live hold`() {
        val longer = state(
            holdUntilMillis = at(15),
            holdReason = COOLDOWN,
            holdPackageNames = setOf(instagram),
        )

        val result = evaluate(
            state = longer,
            now = at(9, 10),
            sessions = listOf(session(instagram, at(9), at(9, 10))),
        )

        assertEquals(BlockDecision.Blocked(at(10, 10), COOLDOWN), result.decision)
        assertEquals(at(15), result.state.holdUntilMillis)
    }

    @Test
    fun `the limit crossing is detected late - usage may exceed the limit by the detection lag`() {
        // The 5 s detection lag: the evaluator sees 10 min 4 s used and still blocks for the whole cooldown.
        val result = evaluate(now = at(9, 10) + 4_000, sessions = listOf(session(instagram, at(9), at(9, 10) + 4_000)))

        assertEquals(BlockDecision.Blocked(at(10, 10) + 4_000, COOLDOWN), result.decision)
    }
}

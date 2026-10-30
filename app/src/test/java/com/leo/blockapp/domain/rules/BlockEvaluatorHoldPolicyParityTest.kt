package com.leo.blockapp.domain.rules

import com.leo.blockapp.domain.model.ActiveHold
import com.leo.blockapp.domain.model.BlockDecision
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockReason.COOLDOWN
import com.leo.blockapp.domain.model.BlockReason.DAILY_CAP
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.usage.ForegroundSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The evaluator and [HoldPolicy] read the same persisted fields, so they must never disagree on a hold. */
class BlockEvaluatorHoldPolicyParityTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val startOfDay = 100 * 24 * hour
    private val startOfNextDay = startOfDay + 24 * hour
    private val instagram = "com.instagram.android"
    private val blockId = "block-1"
    private val rule = BlockRule.AccumulatedUsageWithCooldown(10, 60, dailyCycleCap = 3)

    private fun at(hours: Int, minutes: Int = 0) = startOfDay + hours * hour + minutes * minute

    private fun state(
        cooldownEndMillis: Long? = null,
        cyclesToday: Int = 1,
        holdUntilMillis: Long? = null,
        holdReason: BlockReason? = null,
    ) = BlockState(
        cycleStartMillis = at(8),
        cooldownEndMillis = cooldownEndMillis,
        cyclesToday = cyclesToday,
        cyclesDayStartMillis = startOfDay,
        holdUntilMillis = holdUntilMillis,
        holdReason = holdReason,
        holdPackageNames = if (holdUntilMillis != null) setOf(instagram) else emptySet(),
        blockName = "Social",
    )

    private fun evaluate(state: BlockState, now: Long, sessions: List<ForegroundSession> = emptyList()) =
        BlockEvaluator.evaluate(
            rule = rule,
            blockName = "Social",
            packageNames = setOf(instagram),
            joinedAtMillisByPackage = emptyMap(),
            state = state,
            sessions = sessions,
            nowMillis = now,
            startOfDayMillis = startOfDay,
            startOfNextDayMillis = startOfNextDay,
        )

    private fun assertParity(state: BlockState, now: Long) {
        val hold = HoldPolicy.holdOf(blockId, state, now)
        val decision = evaluate(state, now).decision

        if (hold == null) {
            assertEquals(false, decision is BlockDecision.Blocked)
        } else {
            assertEquals(BlockDecision.Blocked(hold.untilMillis, hold.reason), decision)
        }
    }

    @Test
    fun `a running cooldown is the same verdict for both`() {
        assertParity(state(cooldownEndMillis = at(11), holdUntilMillis = at(11), holdReason = COOLDOWN), now = at(10))
    }

    @Test
    fun `a daily cap hold is the same verdict for both`() {
        assertParity(
            state(cyclesToday = 3, holdUntilMillis = startOfNextDay, holdReason = DAILY_CAP),
            now = at(21),
        )
    }

    @Test
    fun `a running cooldown wins over a longer cap hold for both`() {
        val capped = state(
            cooldownEndMillis = at(21, 30),
            cyclesToday = 3,
            holdUntilMillis = startOfNextDay,
            holdReason = DAILY_CAP,
        )

        assertParity(capped, now = at(21))
        assertParity(capped, now = at(22))
    }

    @Test
    fun `an expired hold and no hold are the same verdict for both`() {
        assertParity(state(holdUntilMillis = at(9), holdReason = COOLDOWN), now = at(10))
        assertParity(state(), now = at(10))
    }

    @Test
    fun `the state written when a limit is crossed reads back as the same hold`() {
        val crossed = evaluate(state(), now = at(9, 10), sessions = listOf(ForegroundSession(instagram, at(9), at(9, 10))))

        val decision = crossed.decision as BlockDecision.Blocked
        val hold = HoldPolicy.holdOf(blockId, crossed.state, at(9, 10))

        assertEquals(ActiveHold(blockId, "Social", decision.untilMillis, decision.reason), hold)
    }

    @Test
    fun `the state written when the cap is reached reads back as the cooldown then the cap`() {
        val third = evaluate(
            state(cyclesToday = 2),
            now = at(20, 10),
            sessions = listOf(ForegroundSession(instagram, at(20), at(20, 10))),
        )

        val during = HoldPolicy.holdOf(blockId, third.state, at(20, 10))
        val after = HoldPolicy.holdOf(blockId, third.state, at(21, 11))

        assertEquals(third.decision, BlockDecision.Blocked(during!!.untilMillis, during.reason))
        assertEquals(COOLDOWN, during.reason)
        assertEquals(ActiveHold(blockId, "Social", startOfNextDay, DAILY_CAP), after)
        assertEquals(BlockDecision.Blocked(startOfNextDay, DAILY_CAP), evaluate(third.state, now = at(21, 11)).decision)
    }

    @Test
    fun `once every hold has ended neither side blocks`() {
        val third = evaluate(
            state(cyclesToday = 2),
            now = at(20, 10),
            sessions = listOf(ForegroundSession(instagram, at(20), at(20, 10))),
        )

        assertNull(HoldPolicy.holdOf(blockId, third.state, startOfNextDay))
    }
}

package com.leo.blockapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockStateTest {

    @Test
    fun `initial state starts the cycle at the start of today with no cooldown or hold`() {
        val state = BlockState.initial(blockName = "Social", startOfTodayMillis = 1_000L)

        assertEquals(
            BlockState(
                cycleStartMillis = 1_000L,
                cooldownEndMillis = null,
                cyclesToday = 0,
                cyclesDayStartMillis = 1_000L,
                holdUntilMillis = null,
                holdReason = null,
                holdPackageNames = emptySet(),
                blockName = "Social",
            ),
            state,
        )
    }

    @Test
    fun `evaluation pairs a decision with the resulting state`() {
        val state = BlockState.initial("Social", 0L)
        val evaluation = BlockEvaluation(BlockDecision.Blocked(untilMillis = 5L, reason = BlockReason.COOLDOWN), state)

        assertEquals(BlockDecision.Blocked(5L, BlockReason.COOLDOWN), evaluation.decision)
        assertEquals(state, evaluation.state)
    }

    @Test
    fun `allow carries the remaining time`() {
        assertEquals(42L, BlockDecision.Allow(remainingMillis = 42L).remainingMillis)
    }

    @Test
    fun `active hold carries its owner block and reason`() {
        val hold = ActiveHold("b1", "Social", 10L, BlockReason.DAILY_CAP)

        assertEquals("b1", hold.blockId)
        assertEquals("Social", hold.blockName)
        assertEquals(10L, hold.untilMillis)
        assertEquals(BlockReason.DAILY_CAP, hold.reason)
    }
}

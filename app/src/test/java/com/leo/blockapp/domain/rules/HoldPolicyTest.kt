package com.leo.blockapp.domain.rules

import com.leo.blockapp.domain.model.ActiveHold
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockReason.COOLDOWN
import com.leo.blockapp.domain.model.BlockReason.DAILY_CAP
import com.leo.blockapp.domain.model.BlockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoldPolicyTest {

    private val nowMillis = 1_000L
    private val instagram = "com.instagram.android"
    private val x = "com.twitter.android"

    private fun state(
        blockName: String = "Social",
        cooldownEndMillis: Long? = null,
        holdUntilMillis: Long? = null,
        holdReason: BlockReason? = null,
        holdPackageNames: Set<String> = emptySet(),
    ) = BlockState(
        cycleStartMillis = 0L,
        cooldownEndMillis = cooldownEndMillis,
        cyclesToday = 1,
        cyclesDayStartMillis = 0L,
        holdUntilMillis = holdUntilMillis,
        holdReason = holdReason,
        holdPackageNames = holdPackageNames,
        blockName = blockName,
    )

    private fun cooldownState(untilMillis: Long, blockName: String = "Social", packages: Set<String> = setOf(instagram)) =
        state(
            blockName,
            cooldownEndMillis = untilMillis,
            holdUntilMillis = untilMillis,
            holdReason = COOLDOWN,
            holdPackageNames = packages,
        )

    private fun capState(untilMillis: Long, blockName: String = "Social", packages: Set<String> = setOf(instagram)) =
        state(blockName, holdUntilMillis = untilMillis, holdReason = DAILY_CAP, holdPackageNames = packages)

    @Test
    fun `no states means no hold`() {
        assertNull(HoldPolicy.activeHold(emptyMap(), instagram, nowMillis))
    }

    @Test
    fun `an active cooldown hold covers its package`() {
        val hold = HoldPolicy.activeHold(mapOf("b1" to cooldownState(5_000L)), instagram, nowMillis)

        assertEquals(ActiveHold("b1", "Social", 5_000L, COOLDOWN), hold)
    }

    @Test
    fun `a hold does not cover packages outside holdPackageNames`() {
        assertNull(HoldPolicy.activeHold(mapOf("b1" to cooldownState(5_000L)), x, nowMillis))
    }

    @Test
    fun `a hold that ends exactly now is not active`() {
        assertNull(HoldPolicy.activeHold(mapOf("b1" to capState(nowMillis)), instagram, nowMillis))
    }

    @Test
    fun `a state without hold fields holds nothing`() {
        val noHold = state(holdPackageNames = setOf(instagram))

        assertNull(HoldPolicy.activeHold(mapOf("b1" to noHold), instagram, nowMillis))
    }

    @Test
    fun `a hold without reason fails closed as a daily cap`() {
        val inconsistent = state(holdUntilMillis = 5_000L, holdPackageNames = setOf(instagram))

        assertEquals(
            ActiveHold("b1", "Social", 5_000L, DAILY_CAP),
            HoldPolicy.activeHold(mapOf("b1" to inconsistent), instagram, nowMillis),
        )
    }

    @Test
    fun `a running cooldown without hold fields still holds until the cooldown end`() {
        val inconsistent = state(cooldownEndMillis = 3_000L, holdPackageNames = setOf(instagram))

        assertEquals(
            ActiveHold("b1", "Social", 3_000L, COOLDOWN),
            HoldPolicy.activeHold(mapOf("b1" to inconsistent), instagram, nowMillis),
        )
    }

    @Test
    fun `a running cooldown with an expired hold still holds until the cooldown end`() {
        val inconsistent = state(
            cooldownEndMillis = 3_000L,
            holdUntilMillis = 500L,
            holdReason = DAILY_CAP,
            holdPackageNames = setOf(instagram),
        )

        assertEquals(ActiveHold("b1", "Social", 3_000L, COOLDOWN), HoldPolicy.holdOf("b1", inconsistent, nowMillis))
    }

    @Test
    fun `a cooldown ending exactly now is no longer active`() {
        val ended = state(cooldownEndMillis = nowMillis, holdPackageNames = setOf(instagram))

        assertNull(HoldPolicy.holdOf("b1", ended, nowMillis))
    }

    @Test
    fun `X9 overlapping holds - the greatest until wins`() {
        val states = mapOf("a" to cooldownState(3_000L), "b" to capState(9_000L))

        assertEquals(ActiveHold("b", "Social", 9_000L, DAILY_CAP), HoldPolicy.activeHold(states, instagram, nowMillis))
    }

    @Test
    fun `X9 overlapping holds - on equal until the cooldown wins over the daily cap`() {
        val states = mapOf("a" to capState(5_000L, "Cap"), "b" to cooldownState(5_000L, "Cool"))

        assertEquals(ActiveHold("b", "Cool", 5_000L, COOLDOWN), HoldPolicy.activeHold(states, instagram, nowMillis))
    }

    @Test
    fun `X9 overlapping holds - on equal until and reason the lowest block id wins`() {
        val states = mapOf("b" to capState(5_000L, "B"), "a" to capState(5_000L, "A"))

        assertEquals(ActiveHold("a", "A", 5_000L, DAILY_CAP), HoldPolicy.activeHold(states, instagram, nowMillis))
    }

    @Test
    fun `an expired hold does not beat an active one`() {
        val states = mapOf("a" to capState(500L), "b" to cooldownState(2_000L))

        assertEquals("b", HoldPolicy.activeHold(states, instagram, nowMillis)?.blockId)
    }

    @Test
    fun `the owner block own hold is included`() {
        val states = mapOf("owner" to capState(9_000L, "Owner", setOf(instagram, x)))

        assertEquals("owner", HoldPolicy.activeHold(states, x, nowMillis)?.blockId)
    }

    @Test
    fun `a hold of a deleted block still reports its stored name`() {
        val states = mapOf("deleted" to capState(9_000L, "Old name"))

        assertEquals("Old name", HoldPolicy.activeHold(states, instagram, nowMillis)?.blockName)
    }

    @Test
    fun `a running cooldown takes reason precedence over a longer daily cap hold`() {
        val state = state(
            cooldownEndMillis = 3_000L,
            holdUntilMillis = 9_000L,
            holdReason = DAILY_CAP,
            holdPackageNames = setOf(instagram),
        )

        assertEquals(ActiveHold("b1", "Social", 3_000L, COOLDOWN), HoldPolicy.holdOf("b1", state, nowMillis))
    }

    @Test
    fun `once the cooldown ended the daily cap hold shows`() {
        val state = state(
            cooldownEndMillis = 900L,
            holdUntilMillis = 9_000L,
            holdReason = DAILY_CAP,
            holdPackageNames = setOf(instagram),
        )

        assertEquals(ActiveHold("b1", "Social", 9_000L, DAILY_CAP), HoldPolicy.holdOf("b1", state, nowMillis))
    }

    @Test
    fun `holdOf returns null when the hold is not active`() {
        assertNull(HoldPolicy.holdOf("b1", capState(nowMillis), nowMillis))
        assertNull(HoldPolicy.holdOf("b1", state(), nowMillis))
    }

    @Test
    fun `activeHold uses the same reason precedence as holdOf`() {
        val state = state(
            cooldownEndMillis = 3_000L,
            holdUntilMillis = 9_000L,
            holdReason = DAILY_CAP,
            holdPackageNames = setOf(instagram),
        )

        assertEquals(
            HoldPolicy.holdOf("b1", state, nowMillis),
            HoldPolicy.activeHold(mapOf("b1" to state), instagram, nowMillis),
        )
    }
}

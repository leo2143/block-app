package com.leo.blockapp.domain.rules

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockScreenState
import com.leo.blockapp.domain.model.BlockState
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockStatusResolverTest {

    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val startOfToday = 10 * day
    private val now = startOfToday + 12 * 60 * minute
    private val instagram = "com.instagram.android"
    private val x = "com.twitter.android"

    private val rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60)

    private fun block(id: String, name: String, vararg packageNames: String) = Block(
        id = id,
        name = name,
        packageNames = linkedSetOf(*packageNames),
        rule = rule,
        position = id.removePrefix("B").toLong(),
    )

    private fun cooldownState(untilMillis: Long, storedName: String, vararg packageNames: String) =
        BlockState.initial(storedName, startOfToday).copy(
            cooldownEndMillis = untilMillis,
            cyclesToday = 1,
            holdUntilMillis = untilMillis,
            holdReason = BlockReason.COOLDOWN,
            holdPackageNames = setOf(*packageNames),
        )

    private fun capState(untilMillis: Long, storedName: String, vararg packageNames: String) =
        BlockState.initial(storedName, startOfToday).copy(
            holdUntilMillis = untilMillis,
            holdReason = BlockReason.DAILY_CAP,
            holdPackageNames = setOf(*packageNames),
        )

    @Test
    fun `a running cooldown is active with the remaining time computed at query time`() {
        val until = now + 35 * minute

        val result = BlockStatusResolver.resolve(
            packageName = instagram,
            blocks = listOf(block("B1", "Social", instagram)),
            states = mapOf("B1" to cooldownState(until, "Social", instagram)),
            nowMillis = now,
        )

        assertEquals(BlockScreenState.Active("Social", until, 35 * minute, BlockReason.COOLDOWN), result)
    }

    @Test
    fun `a daily cap hold is active with its reason`() {
        val until = startOfToday + day

        val result = BlockStatusResolver.resolve(
            packageName = instagram,
            blocks = listOf(block("B1", "Social", instagram)),
            states = mapOf("B1" to capState(until, "Social", instagram)),
            nowMillis = now,
        )

        assertEquals(BlockScreenState.Active("Social", until, until - now, BlockReason.DAILY_CAP), result)
    }

    @Test
    fun `a package with no state is inactive`() {
        val result = BlockStatusResolver.resolve(
            packageName = instagram,
            blocks = listOf(block("B1", "Social", instagram)),
            states = emptyMap(),
            nowMillis = now,
        )

        assertEquals(BlockScreenState.Inactive, result)
    }

    @Test
    fun `a hold that ended is inactive`() {
        val result = BlockStatusResolver.resolve(
            packageName = instagram,
            blocks = listOf(block("B1", "Social", instagram)),
            states = mapOf("B1" to cooldownState(now, "Social", instagram)),
            nowMillis = now,
        )

        assertEquals(BlockScreenState.Inactive, result)
    }

    @Test
    fun `a hold of another package does not apply`() {
        val result = BlockStatusResolver.resolve(
            packageName = x,
            blocks = listOf(block("B1", "Social", instagram)),
            states = mapOf("B1" to cooldownState(now + minute, "Social", instagram)),
            nowMillis = now,
        )

        assertEquals(BlockScreenState.Inactive, result)
    }

    @Test
    fun `A4 the live config name wins over the stored one`() {
        val result = BlockStatusResolver.resolve(
            packageName = instagram,
            blocks = listOf(block("B1", "Renamed", instagram)),
            states = mapOf("B1" to cooldownState(now + minute, "Old name", instagram)),
            nowMillis = now,
        ) as BlockScreenState.Active

        assertEquals("Renamed", result.blockName)
    }

    @Test
    fun `A4 a deleted block falls back to the stored name`() {
        val result = BlockStatusResolver.resolve(
            packageName = instagram,
            blocks = emptyList(),
            states = mapOf("B1" to capState(now + minute, "Deleted social", instagram)),
            nowMillis = now,
        ) as BlockScreenState.Active

        assertEquals("Deleted social", result.blockName)
        assertEquals(BlockReason.DAILY_CAP, result.reason)
    }

    @Test
    fun `a paused block keeps its hold and its live name`() {
        val paused = block("B1", "Social", instagram).copy(enabled = false)

        val result = BlockStatusResolver.resolve(
            packageName = instagram,
            blocks = listOf(paused),
            states = mapOf("B1" to cooldownState(now + minute, "Social", instagram)),
            nowMillis = now,
        )

        assertEquals(BlockScreenState.Active("Social", now + minute, minute, BlockReason.COOLDOWN), result)
    }

    @Test
    fun `an app removed from its block stays blocked under the stored hold packages`() {
        val result = BlockStatusResolver.resolve(
            packageName = instagram,
            blocks = listOf(block("B1", "Social", x)),
            states = mapOf("B1" to cooldownState(now + minute, "Social", instagram, x)),
            nowMillis = now,
        )

        assertEquals(BlockScreenState.Active("Social", now + minute, minute, BlockReason.COOLDOWN), result)
    }

    @Test
    fun `X9 overlapping holds resolve to the same winner as the hold policy`() {
        val states = mapOf(
            "B1" to cooldownState(now + 30 * minute, "First", instagram),
            "B2" to capState(now + 90 * minute, "Second", instagram),
        )
        val blocks = listOf(block("B1", "First", instagram), block("B2", "Second", instagram))

        val result = BlockStatusResolver.resolve(instagram, blocks, states, now)

        val winner = HoldPolicy.activeHold(states, instagram, now)!!
        assertEquals(
            BlockScreenState.Active("Second", winner.untilMillis, winner.untilMillis - now, winner.reason),
            result,
        )
    }

    @Test
    fun `a blank stored name is passed through for the ui fallback`() {
        val result = BlockStatusResolver.resolve(
            packageName = instagram,
            blocks = emptyList(),
            states = mapOf("B1" to capState(now + minute, "", instagram)),
            nowMillis = now,
        ) as BlockScreenState.Active

        assertEquals("", result.blockName)
    }
}

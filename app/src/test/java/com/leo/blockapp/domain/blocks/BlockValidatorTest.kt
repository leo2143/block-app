package com.leo.blockapp.domain.blocks

import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockViolation
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockValidatorTest {

    private fun draft(
        name: String = "Redes",
        packageNames: Set<String> = setOf("com.instagram.android"),
        usageLimitMinutes: Int = 10,
        cooldownMinutes: Int = 60,
        dailyCycleCap: Int? = null,
        inactivityResetMinutes: Int? = null,
    ) = BlockDraft(
        id = null,
        name = name,
        packageNames = packageNames,
        rule = BlockRule.AccumulatedUsageWithCooldown(
            usageLimitMinutes = usageLimitMinutes,
            cooldownMinutes = cooldownMinutes,
            dailyCycleCap = dailyCycleCap,
            inactivityResetMinutes = inactivityResetMinutes,
        ),
    )

    private fun violationsOf(draft: BlockDraft) = BlockValidator.validate(draft)

    @Test
    fun `valid draft has no violations`() {
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft()))
    }

    @Test
    fun `usage limit accepts 1 and 1440`() {
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(usageLimitMinutes = 1)))
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(usageLimitMinutes = 1440)))
    }

    @Test
    fun `usage limit rejects 0 and 1441`() {
        val expected = setOf(BlockViolation.USAGE_LIMIT_OUT_OF_RANGE)
        assertEquals(expected, violationsOf(draft(usageLimitMinutes = 0)))
        assertEquals(expected, violationsOf(draft(usageLimitMinutes = 1441)))
    }

    @Test
    fun `cooldown accepts 15 and 1440`() {
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(cooldownMinutes = 15)))
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(cooldownMinutes = 1440)))
    }

    @Test
    fun `cooldown rejects 14 and 1441`() {
        val expected = setOf(BlockViolation.COOLDOWN_OUT_OF_RANGE)
        assertEquals(expected, violationsOf(draft(cooldownMinutes = 14)))
        assertEquals(expected, violationsOf(draft(cooldownMinutes = 1441)))
    }

    @Test
    fun `inactivity reset accepts null and 1`() {
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(inactivityResetMinutes = null)))
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(inactivityResetMinutes = 1)))
    }

    @Test
    fun `inactivity reset accepts 1440`() {
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(inactivityResetMinutes = 1440)))
    }

    @Test
    fun `too long name is reported together with APPS_EMPTY`() {
        assertEquals(
            setOf(BlockViolation.NAME_TOO_LONG, BlockViolation.APPS_EMPTY),
            violationsOf(draft(name = "a".repeat(41), packageNames = emptySet())),
        )
    }

    @Test
    fun `inactivity reset rejects 0 and 1441`() {
        val expected = setOf(BlockViolation.INACTIVITY_RESET_OUT_OF_RANGE)
        assertEquals(expected, violationsOf(draft(inactivityResetMinutes = 0)))
        assertEquals(expected, violationsOf(draft(inactivityResetMinutes = 1441)))
    }

    @Test
    fun `daily cycle cap accepts null 1 and 20`() {
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(dailyCycleCap = null)))
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(dailyCycleCap = 1)))
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(dailyCycleCap = 20)))
    }

    @Test
    fun `daily cycle cap rejects 0 and 21`() {
        val expected = setOf(BlockViolation.DAILY_CYCLE_CAP_OUT_OF_RANGE)
        assertEquals(expected, violationsOf(draft(dailyCycleCap = 0)))
        assertEquals(expected, violationsOf(draft(dailyCycleCap = 21)))
    }

    @Test
    fun `two bad fields are reported together`() {
        assertEquals(
            setOf(BlockViolation.USAGE_LIMIT_OUT_OF_RANGE, BlockViolation.COOLDOWN_OUT_OF_RANGE),
            violationsOf(draft(usageLimitMinutes = 0, cooldownMinutes = 5)),
        )
    }

    @Test
    fun `empty name is blank`() {
        assertEquals(setOf(BlockViolation.NAME_BLANK), violationsOf(draft(name = "")))
    }

    @Test
    fun `whitespace-only name is blank`() {
        assertEquals(setOf(BlockViolation.NAME_BLANK), violationsOf(draft(name = "   \t ")))
    }

    @Test
    fun `name is trimmed before measuring its length`() {
        val fortyCharacters = "a".repeat(40)
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(name = "  $fortyCharacters  ")))
    }

    @Test
    fun `name of 40 characters is accepted and 41 is too long`() {
        assertEquals(emptySet<BlockViolation>(), violationsOf(draft(name = "a".repeat(40))))
        assertEquals(setOf(BlockViolation.NAME_TOO_LONG), violationsOf(draft(name = "a".repeat(41))))
    }

    @Test
    fun `empty apps are reported as APPS_EMPTY`() {
        assertEquals(setOf(BlockViolation.APPS_EMPTY), violationsOf(draft(packageNames = emptySet())))
    }

    @Test
    fun `APPS_EMPTY is reported together with field violations`() {
        assertEquals(
            setOf(BlockViolation.APPS_EMPTY, BlockViolation.USAGE_LIMIT_OUT_OF_RANGE),
            violationsOf(draft(packageNames = emptySet(), usageLimitMinutes = 0)),
        )
    }

    @Test
    fun `blank name with no apps reports both`() {
        assertEquals(
            setOf(BlockViolation.NAME_BLANK, BlockViolation.APPS_EMPTY),
            violationsOf(draft(name = " ", packageNames = emptySet())),
        )
    }
}

package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.blocks.BlockValidator
import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockViolation
import org.junit.Assert.assertEquals
import org.junit.Test

/** ValidateBlock is pure and non-suspend: the editor calls it on every change. */
class ValidateBlockUseCaseTest {

    private val validateBlock = ValidateBlockUseCase()

    private fun draftWith(usageLimitMinutes: Int, packageNames: Set<String> = setOf("a")) = BlockDraft(
        id = null,
        name = "Redes",
        packageNames = packageNames,
        rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = usageLimitMinutes, cooldownMinutes = 60),
    )

    @Test
    fun `valid draft has no violations`() {
        assertEquals(emptySet<BlockViolation>(), validateBlock(draftWith(usageLimitMinutes = 10)))
    }

    @Test
    fun `returns the same violations as the validator`() {
        val draft = draftWith(usageLimitMinutes = 0, packageNames = emptySet())
        assertEquals(BlockValidator.validate(draft), validateBlock(draft))
        assertEquals(
            setOf(BlockViolation.APPS_EMPTY, BlockViolation.USAGE_LIMIT_OUT_OF_RANGE),
            validateBlock(draft),
        )
    }
}

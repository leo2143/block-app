package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockRepository
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GetBlockUseCaseTest {

    private fun block(id: String, vararg packageNames: String, enabled: Boolean = true) = Block(
        id = id,
        name = "Block $id",
        packageNames = linkedSetOf(*packageNames),
        rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60),
        enabled = enabled,
    )

    @Test
    fun `returns the stored block with that id`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b", "c")))

        assertEquals(block("B2", "b", "c"), GetBlockUseCase(repository)("B2"))
    }

    @Test
    fun `returns a paused block too`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a", enabled = false)))

        assertEquals(block("B1", "a", enabled = false), GetBlockUseCase(repository)("B1"))
    }

    @Test
    fun `returns null for an unknown id`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))

        assertNull(GetBlockUseCase(repository)("B9"))
    }
}

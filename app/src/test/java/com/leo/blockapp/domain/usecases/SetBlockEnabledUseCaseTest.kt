package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockRepository
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class SetBlockEnabledUseCaseTest {

    private val richRule = BlockRule.AccumulatedUsageWithCooldown(
        usageLimitMinutes = 25,
        cooldownMinutes = 45,
        dailyCycleCap = 3,
        inactivityResetMinutes = 30,
    )

    private fun block(id: String, vararg packageNames: String, enabled: Boolean = true, position: Long = 0) = Block(
        id = id,
        name = "Block $id",
        packageNames = linkedSetOf(*packageNames),
        rule = richRule,
        position = position,
        enabled = enabled,
    )

    @Test
    fun `pausing changes only the enabled flag`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "c", "a", "b"), block("B2", "d")))

        SetBlockEnabledUseCase(repository)("B1", enabled = false)

        assertEquals(listOf(block("B1", "c", "a", "b", enabled = false), block("B2", "d")), repository.getBlocks())
        assertEquals(listOf("c", "a", "b"), repository.getBlocks()[0].packageNames.toList())
        assertEquals(1, repository.writes.size)
        assertEquals(listOf("B1"), repository.writes.single().upserts.map { it.id })
    }

    @Test
    fun `resuming a paused block enables it again`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a", enabled = false)))

        SetBlockEnabledUseCase(repository)("B1", enabled = true)

        assertEquals(listOf(block("B1", "a", enabled = true)), repository.getBlocks())
    }

    @Test
    fun `an unknown id is a no-op with no write`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))

        SetBlockEnabledUseCase(repository)("B9", enabled = false)

        assertEquals(listOf(block("B1", "a")), repository.getBlocks())
        assertEquals(0, repository.writes.size)
    }

    @Test
    fun `rapid toggles of one block run one at a time and end on the last requested value`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")), beforeApply = { yield() })
        val setBlockEnabled = SetBlockEnabledUseCase(repository)

        // Requested values in call order: false, true, false, ..., false. The last request disables the block.
        List(9) { index -> async { setBlockEnabled("B1", enabled = index % 2 == 1) } }.awaitAll()

        assertEquals(false, repository.getBlocks().single().enabled)
        assertEquals(9, repository.writes.size)
        assertEquals(1, repository.maxConcurrentUpdates)
    }

    @Test
    fun `setting the value a block already has writes nothing`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b", enabled = false)))
        val setBlockEnabled = SetBlockEnabledUseCase(repository)

        setBlockEnabled("B1", enabled = true)
        setBlockEnabled("B2", enabled = false)

        assertEquals(0, repository.writes.size)
        assertEquals(listOf(block("B1", "a"), block("B2", "b", enabled = false)), repository.getBlocks())
    }

    @Test
    fun `toggles of different blocks racing each other lose nothing`() = runTest {
        val repository = FakeBlockRepository(
            listOf(block("B1", "a"), block("B2", "b"), block("B3", "c")),
            beforeApply = { yield() },
        )
        val setBlockEnabled = SetBlockEnabledUseCase(repository)

        listOf(
            async { setBlockEnabled("B1", enabled = false) },
            async { setBlockEnabled("B2", enabled = false) },
            async { setBlockEnabled("B3", enabled = false) },
        ).awaitAll()

        assertEquals(listOf(false, false, false), repository.getBlocks().map { it.enabled })
    }

    @Test
    fun `a toggle racing a delete of another block loses neither`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a"), block("B2", "b")), beforeApply = { yield() })

        listOf(
            async { SetBlockEnabledUseCase(repository)("B1", enabled = false) },
            async { DeleteBlockUseCase(repository)("B2") },
        ).awaitAll()

        assertEquals(listOf(block("B1", "a", enabled = false)), repository.getBlocks())
    }
}

package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockRepository
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveBlocksUseCaseTest {

    private fun block(id: String, vararg packageNames: String, position: Long = 0) = Block(
        id = id,
        name = "Block $id",
        packageNames = linkedSetOf(*packageNames),
        rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60),
        position = position,
    )

    @Test
    fun `emits the stored blocks first and again after every change`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a")))
        val emissions = mutableListOf<List<Block>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ObserveBlocksUseCase(repository)().toList(emissions)
        }

        repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B2", "b"))), Unit) }
        repository.update { BlockUpdate(BlockChanges(deletedIds = setOf("B1")), Unit) }

        assertEquals(
            listOf(
                listOf(block("B1", "a")),
                listOf(block("B1", "a"), block("B2", "b")),
                listOf(block("B2", "b")),
            ),
            emissions,
        )
    }

    @Test
    fun `emits an empty list when nothing is stored`() = runTest {
        val emissions = mutableListOf<List<Block>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ObserveBlocksUseCase(FakeBlockRepository())().toList(emissions)
        }

        assertEquals(listOf(emptyList<Block>()), emissions)
    }
}

package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.adapters.input.ui.MainDispatcherRule
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.ports.input.ObserveBlocks
import com.leo.blockapp.domain.ports.input.SetBlockEnabled
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BlocksListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60)

    private fun block(id: String, packages: Set<String> = linkedSetOf("pkg.$id"), enabled: Boolean = true) =
        Block(id = id, name = "Name $id", packageNames = packages, rule = rule, position = 0, enabled = enabled)

    private class FakeObserve(val blocks: MutableSharedFlow<List<Block>> = MutableSharedFlow(replay = 1)) : ObserveBlocks {
        override fun invoke(): Flow<List<Block>> = blocks
    }

    private class FakeSetEnabled(private val onSet: suspend (String, Boolean) -> Unit = { _, _ -> }) : SetBlockEnabled {
        val calls = mutableListOf<Pair<String, Boolean>>()

        override suspend fun invoke(id: String, enabled: Boolean) {
            calls += id to enabled
            onSet(id, enabled)
        }
    }

    private fun TestScope.keepStateHot(vm: BlocksListViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
    }

    private fun TestScope.collectEvents(vm: BlocksListViewModel): List<BlocksListEvent> {
        val events = mutableListOf<BlocksListEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    @Test
    fun `starts loading until the first emission`() = runTest {
        val vm = BlocksListViewModel(FakeObserve(), FakeSetEnabled())
        keepStateHot(vm)
        advanceUntilIdle()

        assertEquals(BlocksListUiState.Loading, vm.uiState.value)
    }

    @Test
    fun `loaded items carry name, app count and enabled`() = runTest {
        val observe = FakeObserve()
        val vm = BlocksListViewModel(observe, FakeSetEnabled())
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1", linkedSetOf("a", "b")), block("B2", enabled = false)))
        advanceUntilIdle()

        assertEquals(
            BlocksListUiState.Loaded(
                listOf(
                    BlockListItem(id = "B1", name = "Name B1", appCount = 2, enabled = true),
                    BlockListItem(id = "B2", name = "Name B2", appCount = 1, enabled = false),
                ),
            ),
            vm.uiState.value,
        )
    }

    @Test
    fun `no blocks is loaded and empty, not loading`() = runTest {
        val observe = FakeObserve()
        val vm = BlocksListViewModel(observe, FakeSetEnabled())
        keepStateHot(vm)

        observe.blocks.emit(emptyList())
        advanceUntilIdle()

        assertEquals(BlocksListUiState.Loaded(emptyList()), vm.uiState.value)
    }

    @Test
    fun `a block whose app is not installed still counts it`() = runTest {
        val observe = FakeObserve()
        val vm = BlocksListViewModel(observe, FakeSetEnabled())
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1", linkedSetOf("com.removed.app"))))
        advanceUntilIdle()

        val loaded = vm.uiState.value as BlocksListUiState.Loaded
        assertEquals(1, loaded.items.single().appCount)
    }

    @Test
    fun `new emissions replace the items`() = runTest {
        val observe = FakeObserve()
        val vm = BlocksListViewModel(observe, FakeSetEnabled())
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1")))
        advanceUntilIdle()
        observe.blocks.emit(listOf(block("B1", enabled = false)))
        advanceUntilIdle()

        val loaded = vm.uiState.value as BlocksListUiState.Loaded
        assertEquals(false, loaded.items.single().enabled)
    }

    @Test
    fun `toggling calls SetBlockEnabled`() = runTest {
        val setEnabled = FakeSetEnabled()
        val vm = BlocksListViewModel(FakeObserve(), setEnabled)

        vm.setEnabled("B1", false)
        advanceUntilIdle()

        assertEquals(listOf("B1" to false), setEnabled.calls)
    }

    @Test
    fun `a failed toggle emits one error event and does not crash`() = runTest {
        val vm = BlocksListViewModel(FakeObserve(), FakeSetEnabled { _, _ -> throw IOException("disk") })
        val events = collectEvents(vm)

        vm.setEnabled("B1", false)
        advanceUntilIdle()

        assertEquals(listOf(BlocksListEvent.ShowError("No se pudo guardar. Inténtalo de nuevo.")), events)
    }

    @Test
    fun `a successful toggle emits no event`() = runTest {
        val vm = BlocksListViewModel(FakeObserve(), FakeSetEnabled())
        val events = collectEvents(vm)

        vm.setEnabled("B1", true)
        advanceUntilIdle()

        assertTrue(events.isEmpty())
    }
}

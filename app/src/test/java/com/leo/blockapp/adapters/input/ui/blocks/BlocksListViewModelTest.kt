package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.adapters.input.ui.MainDispatcherRule
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockListStatus
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.ports.input.IsAccessibilityServiceEnabled
import com.leo.blockapp.domain.ports.input.IsUsageAccessGranted
import com.leo.blockapp.domain.ports.input.ObserveBlockStatuses
import com.leo.blockapp.domain.ports.input.ObserveBlocks
import com.leo.blockapp.domain.ports.input.SetBlockEnabled
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

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

    /** Statuses that have already answered "nothing is held" unless a test says otherwise. */
    private class FakeStatuses(
        val statuses: MutableSharedFlow<Map<String, BlockListStatus>> =
            MutableSharedFlow<Map<String, BlockListStatus>>(replay = 1).also { it.tryEmit(emptyMap()) },
    ) : ObserveBlockStatuses {
        override fun invoke(): Flow<Map<String, BlockListStatus>> = statuses
    }

    private class FakeSetEnabled(private val onSet: suspend (String, Boolean) -> Unit = { _, _ -> }) : SetBlockEnabled {
        val calls = mutableListOf<Pair<String, Boolean>>()

        override suspend fun invoke(id: String, enabled: Boolean) {
            calls += id to enabled
            onSet(id, enabled)
        }
    }

    private class FakePermissions(var accessibility: Boolean = true, var usageAccess: Boolean = true) {
        val isAccessibilityEnabled = IsAccessibilityServiceEnabled { accessibility }
        val isUsageAccessGranted = IsUsageAccessGranted { usageAccess }
    }

    private fun newViewModel(
        observe: ObserveBlocks = FakeObserve(),
        setEnabled: SetBlockEnabled = FakeSetEnabled(),
        statuses: ObserveBlockStatuses = FakeStatuses(),
        permissions: FakePermissions = FakePermissions(),
    ) = BlocksListViewModel(
        observeBlocks = observe,
        setBlockEnabled = setEnabled,
        observeBlockStatuses = statuses,
        isAccessibilityServiceEnabled = permissions.isAccessibilityEnabled,
        isUsageAccessGranted = permissions.isUsageAccessGranted,
    )

    private fun TestScope.keepStateHot(vm: BlocksListViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }
    }

    private fun TestScope.collectEvents(vm: BlocksListViewModel): List<BlocksListEvent> {
        val events = mutableListOf<BlocksListEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    private fun BlocksListViewModel.loaded() = uiState.value as BlocksListUiState.Loaded

    @Test
    fun `starts loading until the first emission`() = runTest {
        val vm = newViewModel()
        keepStateHot(vm)
        advanceUntilIdle()

        assertEquals(BlocksListUiState.Loading, vm.uiState.value)
    }

    @Test
    fun `loaded items carry name, app count and enabled`() = runTest {
        val observe = FakeObserve()
        val vm = newViewModel(observe)
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1", linkedSetOf("a", "b")), block("B2", enabled = false)))
        advanceUntilIdle()

        assertEquals(
            listOf(
                BlockListItem(id = "B1", name = "Name B1", appCount = 2, enabled = true),
                BlockListItem(id = "B2", name = "Name B2", appCount = 1, enabled = false),
            ),
            vm.loaded().items,
        )
    }

    @Test
    fun `no blocks is loaded and empty, not loading`() = runTest {
        val observe = FakeObserve()
        val vm = newViewModel(observe)
        keepStateHot(vm)

        observe.blocks.emit(emptyList())
        advanceUntilIdle()

        assertEquals(BlocksListUiState.Loaded(emptyList()), vm.uiState.value)
    }

    @Test
    fun `a block whose app is not installed still counts it`() = runTest {
        val observe = FakeObserve()
        val vm = newViewModel(observe)
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1", linkedSetOf("com.removed.app"))))
        advanceUntilIdle()

        assertEquals(1, vm.loaded().items.single().appCount)
    }

    @Test
    fun `new emissions replace the items`() = runTest {
        val observe = FakeObserve()
        val vm = newViewModel(observe)
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1")))
        advanceUntilIdle()
        observe.blocks.emit(listOf(block("B1", enabled = false)))
        advanceUntilIdle()

        assertEquals(false, vm.loaded().items.single().enabled)
    }

    @Test
    fun `toggling calls SetBlockEnabled`() = runTest {
        val setEnabled = FakeSetEnabled()
        val vm = newViewModel(setEnabled = setEnabled)

        vm.setEnabled("B1", false)
        advanceUntilIdle()

        assertEquals(listOf("B1" to false), setEnabled.calls)
    }

    @Test
    fun `a failed toggle emits one error event and does not crash`() = runTest {
        val vm = newViewModel(setEnabled = FakeSetEnabled { _, _ -> throw IOException("disk") })
        val events = collectEvents(vm)

        vm.setEnabled("B1", false)
        advanceUntilIdle()

        assertEquals(listOf(BlocksListEvent.ShowError("No se pudo guardar. Inténtalo de nuevo.")), events)
    }

    @Test
    fun `a successful toggle emits no event`() = runTest {
        val vm = newViewModel()
        val events = collectEvents(vm)

        vm.setEnabled("B1", true)
        advanceUntilIdle()

        assertTrue(events.isEmpty())
    }

    // region Status lines (C5)

    @Test
    fun `a held block carries its status and the others stay normal`() = runTest {
        val observe = FakeObserve()
        val statuses = FakeStatuses()
        val vm = newViewModel(observe, statuses = statuses)
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1"), block("B2")))
        statuses.statuses.emit(mapOf("B1" to BlockListStatus.CoolingDown(1_000L)))
        advanceUntilIdle()

        assertEquals(
            listOf(BlockListStatus.CoolingDown(1_000L), BlockListStatus.Normal),
            vm.loaded().items.map { it.status },
        )
    }

    @Test
    fun `a daily cap status is carried too`() = runTest {
        val observe = FakeObserve()
        val statuses = FakeStatuses()
        val vm = newViewModel(observe, statuses = statuses)
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1")))
        statuses.statuses.emit(mapOf("B1" to BlockListStatus.DailyCapReached(2_000L)))
        advanceUntilIdle()

        assertEquals(BlockListStatus.DailyCapReached(2_000L), vm.loaded().items.single().status)
    }

    @Test
    fun `the line goes away when the status flow reports the hold is over`() = runTest {
        val observe = FakeObserve()
        val statuses = FakeStatuses()
        val vm = newViewModel(observe, statuses = statuses)
        keepStateHot(vm)
        observe.blocks.emit(listOf(block("B1")))
        statuses.statuses.emit(mapOf("B1" to BlockListStatus.CoolingDown(1_000L)))
        advanceUntilIdle()

        statuses.statuses.emit(emptyMap())
        advanceUntilIdle()

        assertEquals(BlockListStatus.Normal, vm.loaded().items.single().status)
    }

    @Test
    fun `a paused block that is still held keeps its line`() = runTest {
        val observe = FakeObserve()
        val statuses = FakeStatuses()
        val vm = newViewModel(observe, statuses = statuses)
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1", enabled = false)))
        statuses.statuses.emit(mapOf("B1" to BlockListStatus.DailyCapReached(2_000L)))
        advanceUntilIdle()

        assertEquals(BlockListStatus.DailyCapReached(2_000L), vm.loaded().items.single().status)
    }

    @Test
    fun `a hold of a block that no longer exists adds no row`() = runTest {
        val observe = FakeObserve()
        val statuses = FakeStatuses()
        val vm = newViewModel(observe, statuses = statuses)
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1")))
        statuses.statuses.emit(mapOf("gone" to BlockListStatus.CoolingDown(1_000L)))
        advanceUntilIdle()

        assertEquals(listOf("B1"), vm.loaded().items.map { it.id })
        assertEquals(BlockListStatus.Normal, vm.loaded().items.single().status)
    }

    @Test
    fun `statuses that answer in time are known from the first loaded state`() = runTest(timeout = 10.seconds) {
        val observe = FakeObserve()
        val vm = newViewModel(observe)
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1")))
        advanceUntilIdle()

        assertTrue(vm.loaded().statusesKnown)
    }

    @Test
    fun `while the statuses are silent the list waits only a short grace period`() = runTest(timeout = 10.seconds) {
        val observe = FakeObserve()
        val vm = newViewModel(observe, statuses = FakeStatuses(MutableSharedFlow()))
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1")))
        advanceTimeBy(BlocksListViewModel.STATUS_GRACE_MILLIS - 1)
        runCurrent()
        assertEquals(BlocksListUiState.Loading, vm.uiState.value)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(vm.uiState.value is BlocksListUiState.Loaded)
    }

    @Test
    fun `unreadable states leave the blocks visible, flagged as unknown and never as free`() = runTest(timeout = 10.seconds) {
        val observe = FakeObserve()
        val setEnabled = FakeSetEnabled()
        val vm = newViewModel(observe, setEnabled = setEnabled, statuses = FakeStatuses(MutableSharedFlow()))
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1"), block("B2", enabled = false)))
        advanceUntilIdle()
        vm.setEnabled("B1", false)
        advanceUntilIdle()

        val loaded = vm.loaded()
        assertEquals(listOf("B1", "B2"), loaded.items.map { it.id })
        assertFalse(loaded.statusesKnown)
        assertEquals(listOf("B1" to false), setEnabled.calls)
    }

    @Test
    fun `statuses that arrive late replace the unknown marker and fill the lines`() = runTest(timeout = 10.seconds) {
        val observe = FakeObserve()
        val statuses = FakeStatuses(MutableSharedFlow())
        val vm = newViewModel(observe, statuses = statuses)
        keepStateHot(vm)
        observe.blocks.emit(listOf(block("B1"), block("B2")))
        advanceUntilIdle()
        assertFalse(vm.loaded().statusesKnown)

        statuses.statuses.emit(mapOf("B1" to BlockListStatus.CoolingDown(1_000L)))
        advanceUntilIdle()

        val loaded = vm.loaded()
        assertTrue(loaded.statusesKnown)
        assertEquals(listOf(BlockListStatus.CoolingDown(1_000L), BlockListStatus.Normal), loaded.items.map { it.status })
    }

    @Test
    fun `known statuses are not forgotten when the flow stays quiet afterwards`() = runTest(timeout = 10.seconds) {
        val observe = FakeObserve()
        val vm = newViewModel(observe)
        keepStateHot(vm)
        observe.blocks.emit(listOf(block("B1")))
        advanceUntilIdle()

        advanceTimeBy(BlocksListViewModel.STATUS_GRACE_MILLIS * 3)
        runCurrent()

        assertTrue(vm.loaded().statusesKnown)
    }

    @Test
    fun `the unknown notice is the agreed Spanish text`() {
        assertEquals("No se pudo leer el estado de los bloqueos", BlockStatusTexts.STATUS_UNKNOWN_NOTICE)
    }

    // endregion

    // region Warnings (O4)

    @Test
    fun `no warning when everything is granted`() = runTest {
        val observe = FakeObserve()
        val vm = newViewModel(observe, permissions = FakePermissions(accessibility = true, usageAccess = true))
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1")))
        advanceUntilIdle()

        assertEquals(emptyList<ListWarning>(), vm.loaded().warnings)
    }

    @Test
    fun `an enabled block with the accessibility service off warns`() = runTest {
        val observe = FakeObserve()
        val vm = newViewModel(observe, permissions = FakePermissions(accessibility = false))
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1")))
        advanceUntilIdle()

        assertEquals(listOf(ListWarning.ACCESSIBILITY_OFF), vm.loaded().warnings)
    }

    @Test
    fun `an enabled block with the usage access missing warns`() = runTest {
        val observe = FakeObserve()
        val vm = newViewModel(observe, permissions = FakePermissions(usageAccess = false))
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1")))
        advanceUntilIdle()

        assertEquals(listOf(ListWarning.USAGE_ACCESS_MISSING), vm.loaded().warnings)
    }

    @Test
    fun `both warnings show together, accessibility first`() = runTest {
        val observe = FakeObserve()
        val vm = newViewModel(observe, permissions = FakePermissions(accessibility = false, usageAccess = false))
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1")))
        advanceUntilIdle()

        assertEquals(
            listOf(ListWarning.ACCESSIBILITY_OFF, ListWarning.USAGE_ACCESS_MISSING),
            vm.loaded().warnings,
        )
    }

    @Test
    fun `no enabled block means no warning even with nothing granted`() = runTest {
        val observe = FakeObserve()
        val vm = newViewModel(observe, permissions = FakePermissions(accessibility = false, usageAccess = false))
        keepStateHot(vm)

        observe.blocks.emit(emptyList())
        advanceUntilIdle()

        assertEquals(emptyList<ListWarning>(), vm.loaded().warnings)
    }

    @Test
    fun `only paused blocks mean no warning`() = runTest {
        val observe = FakeObserve()
        val vm = newViewModel(observe, permissions = FakePermissions(accessibility = false, usageAccess = false))
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1", enabled = false)))
        advanceUntilIdle()

        assertEquals(emptyList<ListWarning>(), vm.loaded().warnings)
    }

    @Test
    fun `enabling the first block raises the warning and pausing the last one clears it`() = runTest {
        val observe = FakeObserve()
        val vm = newViewModel(observe, permissions = FakePermissions(accessibility = false))
        keepStateHot(vm)

        observe.blocks.emit(listOf(block("B1", enabled = false)))
        advanceUntilIdle()
        assertEquals(emptyList<ListWarning>(), vm.loaded().warnings)

        observe.blocks.emit(listOf(block("B1", enabled = true)))
        advanceUntilIdle()
        assertEquals(listOf(ListWarning.ACCESSIBILITY_OFF), vm.loaded().warnings)

        observe.blocks.emit(listOf(block("B1", enabled = false)))
        advanceUntilIdle()
        assertEquals(emptyList<ListWarning>(), vm.loaded().warnings)
    }

    @Test
    fun `returning from settings rechecks both permissions`() = runTest {
        val observe = FakeObserve()
        val permissions = FakePermissions(accessibility = false, usageAccess = false)
        val vm = newViewModel(observe, permissions = permissions)
        keepStateHot(vm)
        observe.blocks.emit(listOf(block("B1")))
        advanceUntilIdle()

        permissions.accessibility = true
        vm.refreshPermissions()
        advanceUntilIdle()
        assertEquals(listOf(ListWarning.USAGE_ACCESS_MISSING), vm.loaded().warnings)

        permissions.usageAccess = true
        vm.refreshPermissions()
        advanceUntilIdle()
        assertEquals(emptyList<ListWarning>(), vm.loaded().warnings)
    }

    @Test
    fun `a permission that is revoked later raises the warning on the next recheck`() = runTest {
        val observe = FakeObserve()
        val permissions = FakePermissions()
        val vm = newViewModel(observe, permissions = permissions)
        keepStateHot(vm)
        observe.blocks.emit(listOf(block("B1")))
        advanceUntilIdle()

        permissions.accessibility = false
        vm.refreshPermissions()
        advanceUntilIdle()

        assertEquals(listOf(ListWarning.ACCESSIBILITY_OFF), vm.loaded().warnings)
    }

    // endregion
}

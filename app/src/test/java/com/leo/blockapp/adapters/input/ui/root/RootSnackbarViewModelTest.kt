package com.leo.blockapp.adapters.input.ui.root

import com.leo.blockapp.adapters.input.ui.MainDispatcherRule
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockChangeSummary
import com.leo.blockapp.domain.model.BlockReference
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.DeletedBlock
import com.leo.blockapp.domain.model.RestoreBlockResult
import com.leo.blockapp.domain.model.SkippedApp
import com.leo.blockapp.domain.ports.input.DeleteBlock
import com.leo.blockapp.domain.ports.input.RestoreBlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class RootSnackbarViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60)
    private val emptySummary = BlockChangeSummary(emptySet(), emptySet(), emptySet(), emptyMap(), emptyMap())

    private fun block(id: String, name: String = "Name $id") =
        Block(id = id, name = name, packageNames = linkedSetOf("pkg.$id"), rule = rule, position = 0)

    private class FakeDelete(private val onDelete: suspend (String) -> DeletedBlock?) : DeleteBlock {
        override suspend fun invoke(id: String): DeletedBlock? = onDelete(id)
    }

    private class FakeRestore(private val onRestore: suspend (DeletedBlock) -> RestoreBlockResult) : RestoreBlock {
        var calls = 0
            private set

        override suspend fun invoke(deletedBlock: DeletedBlock): RestoreBlockResult {
            calls++
            return onRestore(deletedBlock)
        }
    }

    private fun viewModel(delete: DeleteBlock, restore: RestoreBlock) = RootSnackbarViewModel(delete, restore)

    private fun deletingViewModel(
        restore: FakeRestore = FakeRestore { RestoreBlockResult.Restored(it.block, emptySummary) },
    ) = viewModel(FakeDelete { id -> DeletedBlock(block(id)) }, restore)

    /** Collects eagerly: background work on a standard dispatcher is ignored by advanceUntilIdle. */
    private fun TestScope.collectIn(target: MutableList<String>, vm: RootSnackbarViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.toList(target) }
    }

    private fun RootSnackbarViewModel.statuses() = pendingDeletions.value.map { it.id to it.status }

    @Test
    fun `deleting a block queues it as awaiting decision`() = runTest {
        val vm = deletingViewModel()

        vm.deleteBlock("B1")
        advanceUntilIdle()

        assertEquals(listOf("B1" to PendingStatus.AWAITING_DECISION), vm.statuses())
    }

    @Test
    fun `two quick deletes are both queued in order`() = runTest {
        val vm = deletingViewModel()

        vm.deleteBlock("B1")
        vm.deleteBlock("B2")
        advanceUntilIdle()

        assertEquals(listOf("B1", "B2"), vm.pendingDeletions.value.map { it.id })
    }

    @Test
    fun `deleting an unknown block queues nothing`() = runTest {
        val vm = viewModel(FakeDelete { null }, FakeRestore { error("not called") })
        val messages = mutableListOf<String>()
        collectIn(messages, vm)

        vm.deleteBlock("missing")
        advanceUntilIdle()

        assertTrue(vm.pendingDeletions.value.isEmpty())
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `a failed delete posts one message and queues nothing`() = runTest {
        val vm = viewModel(FakeDelete { throw IOException("disk") }, FakeRestore { error("not called") })
        val messages = mutableListOf<String>()
        collectIn(messages, vm)

        vm.deleteBlock("B1")
        advanceUntilIdle()

        assertTrue(vm.pendingDeletions.value.isEmpty())
        assertEquals(listOf("No se pudo guardar. Inténtalo de nuevo."), messages)
    }

    @Test
    fun `undo marks the entry restoring before any suspension`() = runTest {
        val gate = CompletableDeferred<RestoreBlockResult>()
        val vm = deletingViewModel(FakeRestore { gate.await() })
        vm.deleteBlock("B1")
        advanceUntilIdle()
        val deleted = vm.pendingDeletions.value.single().deleted

        vm.onUndo(deleted)

        assertEquals(listOf("B1" to PendingStatus.RESTORING), vm.statuses())
        gate.complete(RestoreBlockResult.Restored(deleted.block, emptySummary))
        advanceUntilIdle()
    }

    @Test
    fun `a second undo while restoring does not restore twice`() = runTest {
        val gate = CompletableDeferred<RestoreBlockResult>()
        val restore = FakeRestore { gate.await() }
        val vm = deletingViewModel(restore)
        vm.deleteBlock("B1")
        advanceUntilIdle()
        val deleted = vm.pendingDeletions.value.single().deleted

        vm.onUndo(deleted)
        vm.onUndo(deleted)
        gate.complete(RestoreBlockResult.Restored(deleted.block, emptySummary))
        advanceUntilIdle()

        assertEquals(1, restore.calls)
    }

    @Test
    fun `a restored block leaves the queue without a message`() = runTest {
        val vm = deletingViewModel()
        val messages = mutableListOf<String>()
        collectIn(messages, vm)
        vm.deleteBlock("B1")
        advanceUntilIdle()

        vm.onUndo(vm.pendingDeletions.value.single().deleted)
        advanceUntilIdle()

        assertTrue(vm.pendingDeletions.value.isEmpty())
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `a partial restore leaves the queue and posts the skipped apps message`() = runTest {
        val skipped = SkippedApp("pkg.x", "Instagram", BlockReference("B2", "B2"))
        val vm = deletingViewModel(
            FakeRestore { RestoreBlockResult.PartiallyRestored(it.block, listOf(skipped), emptySummary) },
        )
        val messages = mutableListOf<String>()
        collectIn(messages, vm)
        vm.deleteBlock("B1")
        advanceUntilIdle()

        vm.onUndo(vm.pendingDeletions.value.single().deleted)
        advanceUntilIdle()

        assertTrue(vm.pendingDeletions.value.isEmpty())
        assertEquals(listOf("Instagram ya está en otro bloqueo (B2)"), messages)
    }

    @Test
    fun `nothing restored leaves the queue and posts the final message`() = runTest {
        val skipped = SkippedApp("pkg.x", "Instagram", BlockReference("B2", "B2"))
        val vm = deletingViewModel(FakeRestore { RestoreBlockResult.NotRestored(listOf(skipped)) })
        val messages = mutableListOf<String>()
        collectIn(messages, vm)
        vm.deleteBlock("B1")
        advanceUntilIdle()

        vm.onUndo(vm.pendingDeletions.value.single().deleted)
        advanceUntilIdle()

        assertTrue(vm.pendingDeletions.value.isEmpty())
        assertEquals(listOf("No se pudo restaurar"), messages)
    }

    @Test
    fun `a failed restore stays queued as restore failed without a message`() = runTest {
        val vm = deletingViewModel(FakeRestore { throw IOException("disk") })
        val messages = mutableListOf<String>()
        collectIn(messages, vm)
        vm.deleteBlock("B1")
        advanceUntilIdle()

        vm.onUndo(vm.pendingDeletions.value.single().deleted)
        advanceUntilIdle()

        assertEquals(listOf("B1" to PendingStatus.RESTORE_FAILED), vm.statuses())
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `retrying a failed restore removes the entry on success`() = runTest {
        var failNext = true
        val vm = deletingViewModel(
            FakeRestore {
                if (failNext) throw IOException("disk")
                RestoreBlockResult.Restored(it.block, emptySummary)
            },
        )
        vm.deleteBlock("B1")
        advanceUntilIdle()
        val deleted = vm.pendingDeletions.value.single().deleted
        vm.onUndo(deleted)
        advanceUntilIdle()
        failNext = false

        vm.onUndo(deleted)
        advanceUntilIdle()

        assertTrue(vm.pendingDeletions.value.isEmpty())
    }

    @Test
    fun `deleting a block again while its restore is in flight keeps only the new deletion`() = runTest {
        val gate = CompletableDeferred<RestoreBlockResult>()
        val vm = deletingViewModel(FakeRestore { gate.await() })
        vm.deleteBlock("B1")
        advanceUntilIdle()
        val deleted = vm.pendingDeletions.value.single().deleted
        vm.onUndo(deleted)

        vm.deleteBlock("B1")
        advanceUntilIdle()
        assertEquals(listOf("B1" to PendingStatus.AWAITING_DECISION), vm.statuses())

        gate.complete(RestoreBlockResult.Restored(deleted.block, emptySummary))
        advanceUntilIdle()

        assertEquals(listOf("B1" to PendingStatus.AWAITING_DECISION), vm.statuses())
    }

    @Test
    fun `a failed restore does not mark a newer deletion of the same block as failed`() = runTest {
        val gate = CompletableDeferred<RestoreBlockResult>()
        val vm = deletingViewModel(FakeRestore { gate.await() })
        vm.deleteBlock("B1")
        advanceUntilIdle()
        vm.onUndo(vm.pendingDeletions.value.single().deleted)
        vm.deleteBlock("B1")
        advanceUntilIdle()

        gate.completeExceptionally(IOException("disk"))
        advanceUntilIdle()

        assertEquals(listOf("B1" to PendingStatus.AWAITING_DECISION), vm.statuses())
    }

    @Test
    fun `dismissing removes only that entry`() = runTest {
        val vm = deletingViewModel()
        vm.deleteBlock("B1")
        vm.deleteBlock("B2")
        advanceUntilIdle()

        vm.onDismissed(vm.pendingDeletions.value.first().deleted)

        assertEquals(listOf("B2"), vm.pendingDeletions.value.map { it.id })
    }

    @Test
    fun `posted messages are one-shot and consumed once`() = runTest {
        val vm = deletingViewModel()
        vm.post("hello")

        assertEquals("hello", vm.messages.first())

        val late = mutableListOf<String>()
        collectIn(late, vm)
        advanceUntilIdle()
        assertTrue(late.isEmpty())

        vm.post("again")
        advanceUntilIdle()
        assertEquals(listOf("again"), late)
    }
}

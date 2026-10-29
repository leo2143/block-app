package com.leo.blockapp.adapters.input.ui.root

import androidx.compose.material3.SnackbarDuration
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.DeletedBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoSnackbarRunnerTest {

    private data class Call(
        val message: String,
        val actionLabel: String,
        val duration: SnackbarDuration,
        val withDismissAction: Boolean,
    )

    private inner class Harness {
        val queue = MutableStateFlow<List<PendingDeletion>>(emptyList())
        val calls = mutableListOf<Call>()
        val undone = mutableListOf<String>()
        val dismissed = mutableListOf<String>()

        /** Scripted outcomes consumed one per presenter call; when exhausted the snackbar stays on screen. */
        val script = ArrayDeque<UndoSnackbarOutcome>()

        val presenter: UndoSnackbarPresenter = { message, actionLabel, duration, withDismissAction ->
            calls += Call(message, actionLabel, duration, withDismissAction)
            script.removeFirstOrNull() ?: awaitCancellation()
        }

        fun newRunner() = UndoSnackbarRunner(
            queue = queue,
            presenter = presenter,
            onUndo = { deleted ->
                undone += deleted.block.id
                queue.update { list -> list.filterNot { it.id == deleted.block.id } }
            },
            onDismissed = { deleted ->
                dismissed += deleted.block.id
                queue.update { list -> list.filterNot { it.id == deleted.block.id } }
            },
        )

        fun add(id: String, status: PendingStatus = PendingStatus.AWAITING_DECISION) {
            queue.update { it + pending(id, status) }
        }
    }

    private fun pending(id: String, status: PendingStatus) = PendingDeletion(
        deleted = DeletedBlock(
            Block(
                id = id,
                name = "Name $id",
                packageNames = linkedSetOf("pkg.$id"),
                rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60),
                position = 0,
            ),
        ),
        status = status,
    )

    private fun TestScope.start(runner: UndoSnackbarRunner): Job = launch { runner.run() }

    @Test
    fun `an undo snackbar shows the block name, Deshacer, Long and no dismiss action`() = runTest {
        val h = Harness()
        h.add("B1")

        val job = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(
            listOf(Call("«Name B1» eliminado", "Deshacer", SnackbarDuration.Long, withDismissAction = false)),
            h.calls,
        )
        job.cancel()
    }

    @Test
    fun `two deletes are shown one at a time in order`() = runTest {
        val h = Harness()
        h.add("B1")
        h.add("B2")
        h.script += UndoSnackbarOutcome.ACTION_PERFORMED

        val job = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(listOf("«Name B1» eliminado", "«Name B2» eliminado"), h.calls.map { it.message })
        assertEquals(listOf("B1"), h.undone)
        job.cancel()
    }

    @Test
    fun `a pressed action calls onUndo with that block`() = runTest {
        val h = Harness()
        h.add("B1")
        h.script += UndoSnackbarOutcome.ACTION_PERFORMED

        val job = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(listOf("B1"), h.undone)
        assertTrue(h.dismissed.isEmpty())
        job.cancel()
    }

    @Test
    fun `an explicit dismiss finalizes the deletion`() = runTest {
        val h = Harness()
        h.add("B1")
        h.script += UndoSnackbarOutcome.DISMISSED

        val job = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(listOf("B1"), h.dismissed)
        assertTrue(h.undone.isEmpty())
        job.cancel()
    }

    @Test
    fun `a timed out undo snackbar finalizes the deletion`() = runTest {
        val h = Harness()
        h.add("B1")
        h.script += UndoSnackbarOutcome.TIMED_OUT

        val job = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(listOf("B1"), h.dismissed)
        assertTrue(h.undone.isEmpty())
        job.cancel()
    }

    @Test
    fun `cancellation never finalizes and a restarted runner re-offers the entry`() = runTest {
        val h = Harness()
        h.add("B1")

        val first = start(h.newRunner())
        advanceUntilIdle()
        first.cancel(CancellationException("tab switch"))
        advanceUntilIdle()

        assertTrue(h.dismissed.isEmpty())
        assertTrue(h.undone.isEmpty())
        assertEquals(1, h.queue.value.size)

        val second = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(listOf("«Name B1» eliminado", "«Name B1» eliminado"), h.calls.map { it.message })
        second.cancel()
    }

    @Test
    fun `entries being restored are never offered`() = runTest {
        val h = Harness()
        h.add("B1", PendingStatus.RESTORING)

        val job = start(h.newRunner())
        advanceUntilIdle()
        assertTrue(h.calls.isEmpty())

        h.add("B2")
        advanceUntilIdle()

        assertEquals(listOf("«Name B2» eliminado"), h.calls.map { it.message })
        job.cancel()
    }

    @Test
    fun `a failed restore shows the retry snackbar with a dismiss action`() = runTest {
        val h = Harness()
        h.add("B1", PendingStatus.RESTORE_FAILED)

        val job = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(
            listOf(Call("No se pudo restaurar.", "Reintentar", SnackbarDuration.Long, withDismissAction = true)),
            h.calls,
        )
        job.cancel()
    }

    @Test
    fun `retry action calls onUndo`() = runTest {
        val h = Harness()
        h.add("B1", PendingStatus.RESTORE_FAILED)
        h.script += UndoSnackbarOutcome.ACTION_PERFORMED

        val job = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(listOf("B1"), h.undone)
        job.cancel()
    }

    @Test
    fun `dismissing the retry snackbar finalizes the deletion`() = runTest {
        val h = Harness()
        h.add("B1", PendingStatus.RESTORE_FAILED)
        h.script += UndoSnackbarOutcome.DISMISSED

        val job = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(listOf("B1"), h.dismissed)
        job.cancel()
    }

    @Test
    fun `a timed out retry snackbar stays queued and is not re-offered by the same runner`() = runTest {
        val h = Harness()
        h.add("B1", PendingStatus.RESTORE_FAILED)
        h.script += UndoSnackbarOutcome.TIMED_OUT

        val job = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(1, h.calls.size)
        assertTrue(h.dismissed.isEmpty())
        assertEquals(listOf("B1"), h.queue.value.map { it.id })
        job.cancel()
    }

    @Test
    fun `a parked entry does not block the entries behind it`() = runTest {
        val h = Harness()
        h.add("B1", PendingStatus.RESTORE_FAILED)
        h.add("B2")
        h.script += UndoSnackbarOutcome.TIMED_OUT

        val job = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(listOf("No se pudo restaurar.", "«Name B2» eliminado"), h.calls.map { it.message })
        job.cancel()
    }

    @Test
    fun `a parked entry is re-offered when the queue gains a new entry`() = runTest {
        val h = Harness()
        h.add("B1", PendingStatus.RESTORE_FAILED)
        h.script += UndoSnackbarOutcome.TIMED_OUT

        val job = start(h.newRunner())
        advanceUntilIdle()
        assertEquals(1, h.calls.size)

        h.add("B2")
        advanceUntilIdle()

        assertEquals(2, h.calls.size)
        assertEquals("No se pudo restaurar.", h.calls[1].message)
        job.cancel()
    }

    @Test
    fun `unpark re-offers parked entries`() = runTest {
        val h = Harness()
        h.add("B1", PendingStatus.RESTORE_FAILED)
        h.script += UndoSnackbarOutcome.TIMED_OUT
        val runner = h.newRunner()

        val job = start(runner)
        advanceUntilIdle()
        assertEquals(1, h.calls.size)

        runner.unpark()
        advanceUntilIdle()

        assertEquals(2, h.calls.size)
        assertEquals("No se pudo restaurar.", h.calls[1].message)
        job.cancel()
    }

    @Test
    fun `a restarted runner re-offers parked entries`() = runTest {
        val h = Harness()
        h.add("B1", PendingStatus.RESTORE_FAILED)
        h.script += UndoSnackbarOutcome.TIMED_OUT

        val first = start(h.newRunner())
        advanceUntilIdle()
        first.cancel()
        advanceUntilIdle()
        assertEquals(1, h.calls.size)

        val second = start(h.newRunner())
        advanceUntilIdle()

        assertEquals(2, h.calls.size)
        second.cancel()
    }

    @Test
    fun `an empty queue shows nothing`() = runTest {
        val h = Harness()

        val job = start(h.newRunner())
        advanceUntilIdle()

        assertFalse(h.calls.isNotEmpty())
        job.cancel()
    }
}

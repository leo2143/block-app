package com.leo.blockapp.adapters.input.ui.root

import androidx.compose.material3.SnackbarDuration
import com.leo.blockapp.adapters.input.ui.MainDispatcherRule
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.DeletedBlock
import com.leo.blockapp.domain.model.RestoreBlockResult
import com.leo.blockapp.domain.ports.input.DeleteBlock
import com.leo.blockapp.domain.ports.input.RestoreBlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/** Wires the real RootSnackbarViewModel to the runner: only the presenter (the Compose host) is scripted. */
class UndoSnackbarRunnerWithViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60)

    private class FakeDelete(private val rule: BlockRule) : DeleteBlock {
        override suspend fun invoke(id: String): DeletedBlock =
            DeletedBlock(Block(id = id, name = "Name $id", packageNames = linkedSetOf("pkg.$id"), rule = rule, position = 0))
    }

    private class FakeRestore(private val gate: CompletableDeferred<RestoreBlockResult>) : RestoreBlock {
        override suspend fun invoke(deletedBlock: DeletedBlock): RestoreBlockResult = gate.await()
    }

    @Test
    fun `a restore that fails is offered again as a retry snackbar and never while in flight`() = runTest {
        val gate = CompletableDeferred<RestoreBlockResult>()
        val vm = RootSnackbarViewModel(FakeDelete(rule), FakeRestore(gate))
        val shown = mutableListOf<Pair<String, String>>()
        val outcomes = ArrayDeque(listOf(UndoSnackbarOutcome.ACTION_PERFORMED))
        val runner = UndoSnackbarRunner(
            queue = vm.pendingDeletions,
            presenter = { message, actionLabel, _: SnackbarDuration, _ ->
                shown += message to actionLabel
                outcomes.removeFirstOrNull() ?: awaitCancellation()
            },
            onUndo = vm::onUndo,
            onDismissed = vm::onDismissed,
        )
        val job = launch { runner.run() }

        vm.deleteBlock("B1")
        advanceUntilIdle()
        assertEquals(listOf("«Name B1» eliminado" to "Deshacer"), shown)
        assertEquals(listOf(PendingStatus.RESTORING), vm.pendingDeletions.value.map { it.status })

        gate.completeExceptionally(IOException("disk"))
        advanceUntilIdle()

        assertEquals(
            listOf("«Name B1» eliminado" to "Deshacer", "No se pudo restaurar." to "Reintentar"),
            shown,
        )
        assertEquals(listOf(PendingStatus.RESTORE_FAILED), vm.pendingDeletions.value.map { it.status })
        job.cancel()
    }
}

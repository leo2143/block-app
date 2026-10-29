package com.leo.blockapp.adapters.input.ui.root

import androidx.compose.material3.SnackbarDuration
import com.leo.blockapp.adapters.input.ui.blocks.RestoreMessages
import com.leo.blockapp.domain.model.DeletedBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

enum class UndoSnackbarOutcome { ACTION_PERFORMED, DISMISSED, TIMED_OUT }

/** Shows one snackbar and suspends until it is gone. The Compose host maps SnackbarHostState to this. */
typealias UndoSnackbarPresenter = suspend (
    message: String,
    actionLabel: String,
    duration: SnackbarDuration,
    withDismissAction: Boolean,
) -> UndoSnackbarOutcome

/**
 * Shows the pending deletions one snackbar at a time, in queue order, and reports what the user did.
 *
 * Cancellation (tab switch, config change, composition leaving) is never a decision: it propagates out of [run]
 * without calling [onUndo] or [onDismissed], so the entry stays queued and a restarted runner offers it again.
 *
 * A retry snackbar that times out is "parked": the entry stays queued as RESTORE_FAILED but this runner does not
 * show it again until [unpark] is called or the queue gains a new entry. A new runner starts with nothing parked.
 *
 * [run] and [unpark] are meant to be called from the main thread.
 */
class UndoSnackbarRunner(
    private val queue: StateFlow<List<PendingDeletion>>,
    private val presenter: UndoSnackbarPresenter,
    private val onUndo: (DeletedBlock) -> Unit,
    private val onDismissed: (DeletedBlock) -> Unit,
) {

    private val parkedIds = mutableSetOf<String>()
    private var knownIds = emptySet<String>()
    private val unparkVersion = MutableStateFlow(0)

    /** Runs until cancelled. */
    suspend fun run() {
        while (true) {
            present(awaitNextEntry())
        }
    }

    /** Makes parked entries eligible again (e.g. the list screen was re-entered). */
    fun unpark() {
        parkedIds.clear()
        unparkVersion.update { it + 1 }
    }

    private suspend fun awaitNextEntry(): PendingDeletion =
        combine(queue, unparkVersion) { entries, _ -> entries }
            .map { entries -> nextOffer(entries) }
            .filterNotNull()
            .first()

    private fun nextOffer(entries: List<PendingDeletion>): PendingDeletion? {
        val ids = entries.map { it.id }.toSet()
        if ((ids - knownIds).isNotEmpty()) parkedIds.clear()
        knownIds = ids
        parkedIds.retainAll(ids)
        return entries.firstOrNull { it.status != PendingStatus.RESTORING && it.id !in parkedIds }
    }

    private suspend fun present(entry: PendingDeletion) {
        when (entry.status) {
            PendingStatus.AWAITING_DECISION -> {
                val outcome = presenter(
                    "«${entry.deleted.block.name}» eliminado",
                    UNDO_LABEL,
                    SnackbarDuration.Long,
                    false,
                )
                when (outcome) {
                    UndoSnackbarOutcome.ACTION_PERFORMED -> onUndo(entry.deleted)
                    UndoSnackbarOutcome.DISMISSED,
                    UndoSnackbarOutcome.TIMED_OUT,
                    -> onDismissed(entry.deleted)
                }
            }
            PendingStatus.RESTORE_FAILED -> {
                val outcome = presenter(
                    RestoreMessages.RETRY_PROMPT,
                    RETRY_LABEL,
                    SnackbarDuration.Long,
                    true,
                )
                when (outcome) {
                    UndoSnackbarOutcome.ACTION_PERFORMED -> onUndo(entry.deleted)
                    UndoSnackbarOutcome.DISMISSED -> onDismissed(entry.deleted)
                    UndoSnackbarOutcome.TIMED_OUT -> parkedIds += entry.id
                }
            }
            PendingStatus.RESTORING -> Unit
        }
    }

    private companion object {
        const val UNDO_LABEL = "Deshacer"
        const val RETRY_LABEL = "Reintentar"
    }
}

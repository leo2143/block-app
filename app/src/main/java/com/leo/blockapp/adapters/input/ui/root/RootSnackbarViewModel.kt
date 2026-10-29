package com.leo.blockapp.adapters.input.ui.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leo.blockapp.adapters.input.ui.blocks.RestoreMessages
import com.leo.blockapp.adapters.input.ui.blocks.WriteFailureMessages
import com.leo.blockapp.domain.model.DeletedBlock
import com.leo.blockapp.domain.ports.input.DeleteBlock
import com.leo.blockapp.domain.ports.input.RestoreBlock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * Activity-scoped owner of the undo queue and of every one-shot snackbar message. It lives at root scope so a tab
 * switch or a screen leaving composition can never finalize or orphan a pending deletion.
 */
@HiltViewModel
class RootSnackbarViewModel @Inject constructor(
    private val deleteBlock: DeleteBlock,
    private val restoreBlock: RestoreBlock,
) : ViewModel() {

    private val _pendingDeletions = MutableStateFlow<List<PendingDeletion>>(emptyList())
    val pendingDeletions: StateFlow<List<PendingDeletion>> = _pendingDeletions.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** One-shot snackbar texts: each one is delivered to a single collector, once. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    fun post(text: String) {
        messageChannel.trySend(text)
    }

    fun deleteBlock(id: String) {
        viewModelScope.launch {
            try {
                val deleted = deleteBlock.invoke(id) ?: return@launch
                // A newer deletion of the same block supersedes any entry still queued for it.
                _pendingDeletions.update { entries ->
                    entries.filterNot { it.id == deleted.block.id } + PendingDeletion(deleted)
                }
            } catch (_: IOException) {
                post(WriteFailureMessages.NOT_SAVED)
            }
        }
    }

    /** Starts (or retries) a restore. The entry is marked RESTORING before anything suspends. */
    fun onUndo(deleted: DeletedBlock) {
        val id = deleted.block.id
        var started = false
        _pendingDeletions.update { entries ->
            started = entries.any { it.id == id && it.status != PendingStatus.RESTORING }
            if (started) entries.map { if (it.id == id) it.copy(status = PendingStatus.RESTORING) else it } else entries
        }
        if (!started) return
        viewModelScope.launch {
            try {
                val result = restoreBlock.invoke(deleted)
                // Only the entry this restore was started for: a newer deletion of the same block must survive.
                _pendingDeletions.update { entries ->
                    entries.filterNot { it.id == id && it.status == PendingStatus.RESTORING }
                }
                RestoreMessages.message(result)?.let(::post)
            } catch (_: IOException) {
                _pendingDeletions.update { entries ->
                    entries.map {
                        if (it.id == id && it.status == PendingStatus.RESTORING) {
                            it.copy(status = PendingStatus.RESTORE_FAILED)
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    /** The deletion is final. Only the runner calls this, on a real dismissal or timeout. */
    fun onDismissed(deleted: DeletedBlock) {
        remove(deleted.block.id)
    }

    private fun remove(id: String) {
        _pendingDeletions.update { entries -> entries.filterNot { it.id == id } }
    }
}

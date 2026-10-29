package com.leo.blockapp.adapters.input.ui.blocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leo.blockapp.domain.ports.input.ObserveBlocks
import com.leo.blockapp.domain.ports.input.SetBlockEnabled
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class BlockListItem(
    val id: String,
    val name: String,
    val appCount: Int,
    val enabled: Boolean,
)

sealed interface BlocksListUiState {
    data object Loading : BlocksListUiState
    data class Loaded(val items: List<BlockListItem>) : BlocksListUiState
}

/** One-shot events: delivered once, never part of the state. The screen forwards them to the root snackbar. */
sealed interface BlocksListEvent {
    data class ShowError(val text: String) : BlocksListEvent
}

/**
 * Input adapter for the blocks list. It only knows input ports. Delete and undo are not here: they live in the
 * root-scoped RootSnackbarViewModel so they survive tab switches.
 */
@HiltViewModel
class BlocksListViewModel @Inject constructor(
    observeBlocks: ObserveBlocks,
    private val setBlockEnabled: SetBlockEnabled,
) : ViewModel() {

    // StateFlow equality already drops repeated identical emissions (e.g. empty lists after read failures).
    val uiState: StateFlow<BlocksListUiState> = observeBlocks()
        .map { blocks ->
            BlocksListUiState.Loaded(
                blocks.map { BlockListItem(it.id, it.name, it.packageNames.size, it.enabled) },
            ) as BlocksListUiState
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BlocksListUiState.Loading)

    private val eventChannel = Channel<BlocksListEvent>(Channel.BUFFERED)
    val events: Flow<BlocksListEvent> = eventChannel.receiveAsFlow()

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                setBlockEnabled(id, enabled)
            } catch (_: IOException) {
                eventChannel.trySend(BlocksListEvent.ShowError(WriteFailureMessages.NOT_SAVED))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

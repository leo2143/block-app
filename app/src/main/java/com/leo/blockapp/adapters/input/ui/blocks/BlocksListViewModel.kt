package com.leo.blockapp.adapters.input.ui.blocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockListStatus
import com.leo.blockapp.domain.ports.input.IsAccessibilityServiceEnabled
import com.leo.blockapp.domain.ports.input.IsUsageAccessGranted
import com.leo.blockapp.domain.ports.input.ObserveBlockStatuses
import com.leo.blockapp.domain.ports.input.ObserveBlocks
import com.leo.blockapp.domain.ports.input.SetBlockEnabled
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    /** [BlockListStatus.Normal] unless a hold is active; the screen draws a line only for the others. */
    val status: BlockListStatus = BlockListStatus.Normal,
)

/** Something the user must fix for their enabled blocks to work. */
enum class ListWarning { ACCESSIBILITY_OFF, USAGE_ACCESS_MISSING }

sealed interface BlocksListUiState {
    data object Loading : BlocksListUiState
    data class Loaded(
        val items: List<BlockListItem>,
        val warnings: List<ListWarning> = emptyList(),
    ) : BlocksListUiState
}

/** One-shot events: delivered once, never part of the state. The screen forwards them to the root snackbar. */
sealed interface BlocksListEvent {
    data class ShowError(val text: String) : BlocksListEvent
}

/**
 * Input adapter for the blocks list. It only knows input ports. Delete and undo are not here: they live in the
 * root-scoped RootSnackbarViewModel so they survive tab switches.
 *
 * The list stays in [BlocksListUiState.Loading] until the block statuses have emitted once: while the stored states
 * cannot be read it is better to wait than to draw a held block as free. The upstream flows only run while the
 * screen is started (WhileSubscribed), so the status wake-up is not kept alive in the background.
 *
 * Warnings are shown only when at least one block is enabled. The permissions are read at creation and again on
 * [refreshPermissions], which the screen calls whenever it resumes (e.g. coming back from the system settings).
 */
@HiltViewModel
class BlocksListViewModel @Inject constructor(
    observeBlocks: ObserveBlocks,
    private val setBlockEnabled: SetBlockEnabled,
    observeBlockStatuses: ObserveBlockStatuses,
    private val isAccessibilityServiceEnabled: IsAccessibilityServiceEnabled,
    private val isUsageAccessGranted: IsUsageAccessGranted,
) : ViewModel() {

    private val permissions = MutableStateFlow(readPermissions())

    // StateFlow equality already drops repeated identical emissions (e.g. empty lists after read failures).
    val uiState: StateFlow<BlocksListUiState> =
        combine(observeBlocks(), observeBlockStatuses(), permissions) { blocks, statuses, permissionState ->
            toLoaded(blocks, statuses, permissionState)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BlocksListUiState.Loading)

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

    /** Re-reads both permissions; the screen calls it on every resume. */
    fun refreshPermissions() {
        permissions.value = readPermissions()
    }

    private fun readPermissions() = PermissionState(
        accessibilityEnabled = isAccessibilityServiceEnabled(),
        usageAccessGranted = isUsageAccessGranted(),
    )

    private fun toLoaded(
        blocks: List<Block>,
        statuses: Map<String, BlockListStatus>,
        permissionState: PermissionState,
    ): BlocksListUiState.Loaded {
        val items = blocks.map {
            BlockListItem(
                id = it.id,
                name = it.name,
                appCount = it.packageNames.size,
                enabled = it.enabled,
                status = statuses[it.id] ?: BlockListStatus.Normal,
            )
        }
        val warnings = if (blocks.any { it.enabled }) {
            listOfNotNull(
                ListWarning.ACCESSIBILITY_OFF.takeUnless { permissionState.accessibilityEnabled },
                ListWarning.USAGE_ACCESS_MISSING.takeUnless { permissionState.usageAccessGranted },
            )
        } else {
            emptyList()
        }
        return BlocksListUiState.Loaded(items, warnings)
    }

    private data class PermissionState(val accessibilityEnabled: Boolean, val usageAccessGranted: Boolean)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

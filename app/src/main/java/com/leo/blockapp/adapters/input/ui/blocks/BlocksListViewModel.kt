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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
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
        /**
         * False while the stored states could not be read: the items' [BlockListItem.status] then means "unknown",
         * not "free", and the screen says so instead of drawing the blocks as normal.
         */
        val statusesKnown: Boolean = true,
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
 * The list waits for the block statuses for a short grace period. If they stay silent (the stored states cannot be
 * read) the blocks are shown anyway, flagged with [BlocksListUiState.Loaded.statusesKnown] = false, so the user can
 * still pause, edit or delete them; the screen then says the state is unknown and never draws a block as free. The
 * upstream flows only run while the screen is started (WhileSubscribed), so the status wake-up is not kept alive in
 * the background.
 *
 * Warnings are shown only when at least one block is enabled. The permissions are read at creation and again on
 * [refreshPermissions], which the screen calls whenever it resumes (e.g. coming back from the system settings).
 */
@HiltViewModel
class BlocksListViewModel @Inject constructor(
    observeBlocks: ObserveBlocks,
    private val setBlockEnabled: SetBlockEnabled,
    private val observeBlockStatuses: ObserveBlockStatuses,
    private val isAccessibilityServiceEnabled: IsAccessibilityServiceEnabled,
    private val isUsageAccessGranted: IsUsageAccessGranted,
) : ViewModel() {

    private val permissions = MutableStateFlow(readPermissions())

    // StateFlow equality already drops repeated identical emissions (e.g. empty lists after read failures).
    val uiState: StateFlow<BlocksListUiState> =
        combine(observeBlocks(), statusesOrUnknown(), permissions) { blocks, statuses, permissionState ->
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

    /**
     * The statuses, or null when none arrived within [STATUS_GRACE_MILLIS] (unreadable storage keeps the port silent).
     * A null is only ever sent before the first real emission, so known statuses never flicker back to unknown.
     */
    private fun statusesOrUnknown(): Flow<Map<String, BlockListStatus>?> = channelFlow {
        val unknownAfterGrace = launch {
            delay(STATUS_GRACE_MILLIS)
            send(null)
        }
        observeBlockStatuses().collect { statuses ->
            unknownAfterGrace.cancel()
            send(statuses)
        }
    }

    private fun toLoaded(
        blocks: List<Block>,
        statuses: Map<String, BlockListStatus>?,
        permissionState: PermissionState,
    ): BlocksListUiState.Loaded {
        val items = blocks.map {
            BlockListItem(
                id = it.id,
                name = it.name,
                appCount = it.packageNames.size,
                enabled = it.enabled,
                status = statuses?.get(it.id) ?: BlockListStatus.Normal,
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
        return BlocksListUiState.Loaded(items, warnings, statusesKnown = statuses != null)
    }

    private data class PermissionState(val accessibilityEnabled: Boolean, val usageAccessGranted: Boolean)

    internal companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * How long the list waits for the first statuses before showing the blocks as "unknown". Generous so a slow
         * cold read is not reported as a failure. Once statuses were known, a later storage failure keeps the last
         * known lines (accepted, fail closed: hold lines may linger, never a false "free").
         */
        const val STATUS_GRACE_MILLIS = 4_000L
    }
}

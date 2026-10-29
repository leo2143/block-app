package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.BlockViolation
import com.leo.blockapp.domain.model.MoveConflict
import com.leo.blockapp.domain.model.SelectableApp

sealed interface BlockEditorUiState {

    /** Nothing to show yet, or the editor is closing because the block no longer exists. */
    data object Loading : BlockEditorUiState

    data class Ready(
        val isNew: Boolean,
        val nameText: String,
        val nameEditedByUser: Boolean,
        /** Name derived from the selected apps; what a blank [nameText] stands for. */
        val suggestedName: String,
        val apps: List<SelectableApp>,
        /** Apps selected when the editor opened, in selection order; the picker lists them first. */
        val openedSelection: List<String>,
        val selectedPackages: Set<String>,
        val searchQuery: String,
        val limit: MinutesField,
        val cooldown: MinutesField,
        val capEnabled: Boolean,
        val cap: MinutesField,
        val inactivityEnabled: Boolean,
        val inactivity: MinutesField,
        val visibleViolations: Set<BlockViolation>,
        val touchedFields: Set<EditorField>,
        val submitAttempted: Boolean,
        val pendingMoves: List<MoveConflict>?,
        val isSaving: Boolean,
        val showDiscardDialog: Boolean,
        /** True after a successful save: the screen navigates back without the discard dialog. */
        val saved: Boolean,
    ) : BlockEditorUiState
}

/** One-shot events: delivered once, never part of the state. The screen forwards texts to the root snackbar. */
sealed interface BlockEditorEvent {
    data class ShowError(val text: String) : BlockEditorEvent
    data class CloseWithMessage(val text: String) : BlockEditorEvent
}

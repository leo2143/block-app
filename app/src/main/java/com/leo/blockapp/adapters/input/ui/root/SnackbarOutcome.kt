package com.leo.blockapp.adapters.input.ui.root

import androidx.compose.material3.SnackbarResult

internal object SnackbarOutcome {

    /**
     * SnackbarResult alone cannot tell a dismiss-icon tap from a timeout, so the host records whether the user
     * tapped the icon ([dismissedByUser]). A timeout is not a decision about retry prompts, hence the split.
     */
    fun from(result: SnackbarResult, dismissedByUser: Boolean): UndoSnackbarOutcome = when (result) {
        SnackbarResult.ActionPerformed -> UndoSnackbarOutcome.ACTION_PERFORMED
        SnackbarResult.Dismissed ->
            if (dismissedByUser) UndoSnackbarOutcome.DISMISSED else UndoSnackbarOutcome.TIMED_OUT
    }
}

package com.leo.blockapp.adapters.input.ui.root

import androidx.compose.material3.SnackbarResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SnackbarOutcomeTest {

    @Test
    fun `action performed maps to action performed`() {
        assertEquals(
            UndoSnackbarOutcome.ACTION_PERFORMED,
            SnackbarOutcome.from(SnackbarResult.ActionPerformed, dismissedByUser = false),
        )
    }

    @Test
    fun `action performed wins even if the dismiss flag is stale`() {
        assertEquals(
            UndoSnackbarOutcome.ACTION_PERFORMED,
            SnackbarOutcome.from(SnackbarResult.ActionPerformed, dismissedByUser = true),
        )
    }

    @Test
    fun `dismissed by the user maps to dismissed`() {
        assertEquals(
            UndoSnackbarOutcome.DISMISSED,
            SnackbarOutcome.from(SnackbarResult.Dismissed, dismissedByUser = true),
        )
    }

    @Test
    fun `dismissed without a user tap maps to timed out`() {
        assertEquals(
            UndoSnackbarOutcome.TIMED_OUT,
            SnackbarOutcome.from(SnackbarResult.Dismissed, dismissedByUser = false),
        )
    }
}

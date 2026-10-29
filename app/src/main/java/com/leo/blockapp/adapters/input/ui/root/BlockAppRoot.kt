package com.leo.blockapp.adapters.input.ui.root

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.leo.blockapp.adapters.input.ui.navigation.BlockAppBottomBar
import com.leo.blockapp.adapters.input.ui.navigation.BlockAppNavHost
import com.leo.blockapp.adapters.input.ui.navigation.BlockAppRoutes
import com.leo.blockapp.adapters.input.ui.navigation.navigateToTab

/** Remembers whether the last snackbar was closed with its dismiss icon: SnackbarResult cannot tell it from a timeout. */
private class DismissTracker {
    var byUser = false
}

/**
 * Root of the UI. Everything snackbar-related lives here, above the NavHost, so a tab switch can never cancel the
 * undo runner or the messages collector: both are keyed to this composition, not to a screen.
 */
@Composable
fun BlockAppRoot(rootSnackbarViewModel: RootSnackbarViewModel) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }
    val dismissTracker = remember { DismissTracker() }

    // One runner for the whole composition: the list screen calls unpark() on it when it is re-entered.
    val undoRunner = remember(rootSnackbarViewModel, snackbarHostState) {
        UndoSnackbarRunner(
            queue = rootSnackbarViewModel.pendingDeletions,
            presenter = { message, actionLabel, duration, withDismissAction ->
                dismissTracker.byUser = false
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = actionLabel,
                    withDismissAction = withDismissAction,
                    duration = duration,
                )
                SnackbarOutcome.from(result, dismissTracker.byUser)
            },
            onUndo = rootSnackbarViewModel::onUndo,
            onDismissed = rootSnackbarViewModel::onDismissed,
        )
    }

    // Single collectors, started once. Cancellation (e.g. rotation) never finalizes a deletion: see UndoSnackbarRunner.
    LaunchedEffect(undoRunner) { undoRunner.run() }
    LaunchedEffect(rootSnackbarViewModel, snackbarHostState) {
        rootSnackbarViewModel.messages.collect { text ->
            snackbarHostState.showSnackbar(message = text, duration = SnackbarDuration.Short)
        }
    }

    // The host is rendered by each screen's own Scaffold (snackbarHost slot) so it sits above that screen's FAB or
    // bottom bar; the state, runner and collectors stay here.
    // Remembered so the lambda keeps a stable identity across recomposition. The NavHost renders it only in the
    // current destination.
    val snackbarHost: @Composable () -> Unit = remember(snackbarHostState, dismissTracker) {
        {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    action = data.visuals.actionLabel?.let { label ->
                        { TextButton(onClick = { data.performAction() }) { Text(label) } }
                    },
                    dismissAction = if (data.visuals.withDismissAction) {
                        {
                            IconButton(onClick = {
                                dismissTracker.byUser = true
                                data.dismiss()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Cerrar")
                            }
                        }
                    } else {
                        null
                    },
                ) { Text(data.visuals.message) }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (BlockAppRoutes.showsBottomBar(currentRoute)) {
                BlockAppBottomBar(currentRoute = currentRoute, onTabSelected = navController::navigateToTab)
            }
        },
    ) { innerPadding ->
        BlockAppNavHost(
            navController = navController,
            rootSnackbarViewModel = rootSnackbarViewModel,
            onBlocksListEntered = undoRunner::unpark,
            snackbarHost = snackbarHost,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        )
    }
}

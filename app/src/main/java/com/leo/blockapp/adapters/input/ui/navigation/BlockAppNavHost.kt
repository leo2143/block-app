package com.leo.blockapp.adapters.input.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.leo.blockapp.adapters.input.ui.blocks.BlockEditorScreen
import com.leo.blockapp.adapters.input.ui.blocks.BlocksListScreen
import com.leo.blockapp.adapters.input.ui.root.RootSnackbarViewModel
import com.leo.blockapp.adapters.input.ui.usage.AppUsageScreen

/** Switches between top-level tabs keeping each tab's back stack and state, so list ViewModels survive. */
fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private val EmptySnackbarHost: @Composable () -> Unit = {}

@Composable
fun BlockAppNavHost(
    navController: NavHostController,
    rootSnackbarViewModel: RootSnackbarViewModel,
    onBlocksListEntered: () -> Unit,
    snackbarHost: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    // During a transition the exiting and entering screens are both composed. Only the current entry renders the
    // shared snackbar host, otherwise a visible snackbar would be drawn twice (ghost, double animation, double
    // TalkBack announcement).
    val hostFor: (NavBackStackEntry) -> (@Composable () -> Unit) = { entry ->
        if (entry.id == currentEntry?.id) snackbarHost else EmptySnackbarHost
    }

    NavHost(navController = navController, startDestination = BlockAppRoutes.USAGE, modifier = modifier) {
        composable(BlockAppRoutes.USAGE) { entry -> AppUsageScreen(snackbarHost = hostFor(entry)) }
        composable(BlockAppRoutes.BLOCKS) { entry ->
            BlocksListScreen(
                rootSnackbarViewModel = rootSnackbarViewModel,
                snackbarHost = hostFor(entry),
                // singleTop: a double tap must not stack two editors.
                onAddBlock = {
                    navController.navigate(BlockAppRoutes.editor(null)) { launchSingleTop = true }
                },
                onEditBlock = { blockId ->
                    navController.navigate(BlockAppRoutes.editor(blockId)) { launchSingleTop = true }
                },
                onEntered = onBlocksListEntered,
            )
        }
        composable(
            route = BlockAppRoutes.EDITOR_PATTERN,
            arguments = listOf(
                navArgument(BlockAppRoutes.EDITOR_BLOCK_ID_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            BlockEditorScreen(
                rootSnackbarViewModel = rootSnackbarViewModel,
                snackbarHost = hostFor(entry),
                onClose = {
                    // Guard against a double tap or a late event popping a second destination.
                    if (navController.currentDestination?.route == BlockAppRoutes.EDITOR_PATTERN) {
                        navController.popBackStack()
                    }
                },
            )
        }
    }
}

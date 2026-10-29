package com.leo.blockapp.adapters.input.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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

@Composable
fun BlockAppNavHost(
    navController: NavHostController,
    rootSnackbarViewModel: RootSnackbarViewModel,
    onBlocksListEntered: () -> Unit,
    snackbarHost: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(navController = navController, startDestination = BlockAppRoutes.USAGE, modifier = modifier) {
        composable(BlockAppRoutes.USAGE) { AppUsageScreen(snackbarHost = snackbarHost) }
        composable(BlockAppRoutes.BLOCKS) {
            BlocksListScreen(
                rootSnackbarViewModel = rootSnackbarViewModel,
                snackbarHost = snackbarHost,
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
        ) {
            BlockEditorScreen(
                rootSnackbarViewModel = rootSnackbarViewModel,
                snackbarHost = snackbarHost,
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

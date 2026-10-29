package com.leo.blockapp.adapters.input.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

private data class BottomBarTab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    BottomBarTab(BlockAppRoutes.USAGE, "Uso", Icons.Default.DateRange),
    BottomBarTab(BlockAppRoutes.BLOCKS, "Bloqueos", Icons.Default.Lock),
)

/** Tab bar shown only on the top-level destinations; see [BlockAppRoutes.showsBottomBar]. */
@Composable
fun BlockAppBottomBar(currentRoute: String?, onTabSelected: (route: String) -> Unit) {
    NavigationBar {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onTabSelected(tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}

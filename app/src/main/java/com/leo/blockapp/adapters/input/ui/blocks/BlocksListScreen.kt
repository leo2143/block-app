package com.leo.blockapp.adapters.input.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leo.blockapp.adapters.input.ui.root.RootSnackbarViewModel
import com.leo.blockapp.adapters.input.ui.theme.BlockAppTheme

@Composable
fun BlocksListScreen(
    rootSnackbarViewModel: RootSnackbarViewModel,
    snackbarHost: @Composable () -> Unit,
    onAddBlock: () -> Unit,
    onEditBlock: (blockId: String) -> Unit,
    onEntered: () -> Unit,
    viewModel: BlocksListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnEntered by rememberUpdatedState(onEntered)

    // Coming back to this screen makes failed-restore prompts that timed out eligible again.
    LaunchedEffect(Unit) { currentOnEntered() }

    // post() does not suspend, so an event received here is always handed to the root host.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BlocksListEvent.ShowError -> rootSnackbarViewModel.post(event.text)
            }
        }
    }

    BlocksListContent(
        uiState = uiState,
        snackbarHost = snackbarHost,
        onAddBlock = onAddBlock,
        onEditBlock = onEditBlock,
        onSetEnabled = viewModel::setEnabled,
        onDeleteBlock = rootSnackbarViewModel::deleteBlock,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlocksListContent(
    uiState: BlocksListUiState,
    onAddBlock: () -> Unit,
    onEditBlock: (String) -> Unit,
    onSetEnabled: (id: String, enabled: Boolean) -> Unit,
    onDeleteBlock: (String) -> Unit,
    snackbarHost: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Bloqueos") }) },
        snackbarHost = snackbarHost,
        floatingActionButton = {
            FloatingActionButton(onClick = onAddBlock) {
                Icon(Icons.Default.Add, contentDescription = "Nuevo bloqueo")
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (uiState) {
                BlocksListUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is BlocksListUiState.Loaded ->
                    if (uiState.items.isEmpty()) {
                        EmptyBlocks(Modifier.align(Alignment.Center))
                    } else {
                        BlocksList(uiState.items, onEditBlock, onSetEnabled, onDeleteBlock)
                    }
            }
        }
    }
}

@Composable
private fun EmptyBlocks(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Aún no tienes bloqueos", style = MaterialTheme.typography.titleMedium)
        Text("Toca + para crear el primero.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BlocksList(
    items: List<BlockListItem>,
    onEditBlock: (String) -> Unit,
    onSetEnabled: (id: String, enabled: Boolean) -> Unit,
    onDeleteBlock: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            ListItem(
                modifier = Modifier.clickable { onEditBlock(item.id) },
                headlineContent = { Text(item.name) },
                supportingContent = { Text(appCountText(item.appCount)) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val switchDescription = "Activar ${item.name}"
                        Switch(
                            checked = item.enabled,
                            onCheckedChange = { onSetEnabled(item.id, it) },
                            modifier = Modifier.semantics { contentDescription = switchDescription },
                        )
                        IconButton(onClick = { onDeleteBlock(item.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar ${item.name}")
                        }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

private fun appCountText(count: Int): String = if (count == 1) "1 app" else "$count apps"

@Preview(showBackground = true)
@Composable
private fun BlocksListPreview() {
    BlockAppTheme {
        BlocksListContent(
            uiState = BlocksListUiState.Loaded(
                listOf(
                    BlockListItem("1", "Instagram y X", 2, enabled = true),
                    BlockListItem("2", "Juegos", 1, enabled = false),
                ),
            ),
            onAddBlock = {},
            onEditBlock = {},
            onSetEnabled = { _, _ -> },
            onDeleteBlock = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyBlocksPreview() {
    BlockAppTheme { BlocksListContent(BlocksListUiState.Loaded(emptyList()), {}, {}, { _, _ -> }, {}) }
}

package com.leo.blockapp.adapters.input.ui.usage

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leo.blockapp.adapters.input.ui.theme.BlockAppTheme
import com.leo.blockapp.domain.model.AppUsage
import com.leo.blockapp.domain.model.InstalledApp

@Composable
fun AppUsageScreen(snackbarHost: @Composable () -> Unit, viewModel: AppUsageViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Refresh every time the screen comes back to the foreground, e.g. when returning from Settings.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshUsage()
        onPauseOrDispose { }
    }

    AppUsageContent(
        uiState = uiState,
        snackbarHost = snackbarHost,
        onOpenUsageAccessSettings = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUsageContent(
    uiState: AppUsageUiState,
    onOpenUsageAccessSettings: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Uso de hoy") }) },
        snackbarHost = snackbarHost,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (uiState) {
                AppUsageUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                AppUsageUiState.MissingUsageAccess -> UsageAccessRequest(
                    onOpenUsageAccessSettings = onOpenUsageAccessSettings,
                    modifier = Modifier.align(Alignment.Center),
                )
                is AppUsageUiState.Loaded -> AppUsageList(uiState.appUsages)
            }
        }
    }
}

@Composable
private fun UsageAccessRequest(onOpenUsageAccessSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Necesito el permiso de Acceso de uso", style = MaterialTheme.typography.titleMedium)
        Text(
            "Sirve para saber cuánto tiempo usas cada app. En la lista que se abre, busca Block App y actívalo.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onOpenUsageAccessSettings) { Text("Abrir Ajustes") }
    }
}

@Composable
private fun AppUsageList(appUsages: List<AppUsage>) {
    LazyColumn {
        items(appUsages, key = { it.app.packageName }) { appUsage ->
            ListItem(
                headlineContent = { Text(appUsage.app.label) },
                supportingContent = { Text(appUsage.app.packageName) },
                trailingContent = { Text(formatDuration(appUsage.foregroundMillisToday)) },
            )
            HorizontalDivider()
        }
    }
}

internal fun formatDuration(durationMillis: Long): String {
    val totalMinutes = durationMillis / 60_000
    return when {
        totalMinutes < 1 -> "< 1 min"
        totalMinutes < 60 -> "$totalMinutes min"
        else -> "${totalMinutes / 60} h ${totalMinutes % 60} min"
    }
}

@Preview(showBackground = true)
@Composable
private fun AppUsageListPreview() {
    BlockAppTheme {
        AppUsageContent(
            uiState = AppUsageUiState.Loaded(
                listOf(
                    AppUsage(InstalledApp("com.instagram.android", "Instagram"), 47 * 60_000L),
                    AppUsage(InstalledApp("com.twitter.android", "X"), 12 * 60_000L),
                    AppUsage(InstalledApp("com.whatsapp", "WhatsApp"), 0L),
                ),
            ),
            onOpenUsageAccessSettings = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UsageAccessRequestPreview() {
    BlockAppTheme {
        AppUsageContent(uiState = AppUsageUiState.MissingUsageAccess, onOpenUsageAccessSettings = {})
    }
}

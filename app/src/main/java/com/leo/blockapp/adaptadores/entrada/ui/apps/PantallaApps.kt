package com.leo.blockapp.adaptadores.entrada.ui.apps

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
import com.leo.blockapp.adaptadores.entrada.ui.tema.TemaBlockApp
import com.leo.blockapp.dominio.modelo.AppInstalada
import com.leo.blockapp.dominio.modelo.UsoDeApp

@Composable
fun PantallaApps(viewModel: AppsViewModel = hiltViewModel()) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Se refresca cada vez que la pantalla vuelve a primer plano, por ejemplo al volver de Ajustes.
    LifecycleResumeEffect(Unit) {
        viewModel.refrescar()
        onPauseOrDispose { }
    }

    ContenidoApps(
        estado = estado,
        onPedirPermiso = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContenidoApps(estado: EstadoApps, onPedirPermiso: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Uso de hoy") }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (estado) {
                EstadoApps.Cargando -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                EstadoApps.SinPermiso -> PedirPermiso(onPedirPermiso, Modifier.align(Alignment.Center))
                is EstadoApps.Listo -> ListaApps(estado.apps)
            }
        }
    }
}

@Composable
private fun PedirPermiso(onPedirPermiso: () -> Unit, modifier: Modifier = Modifier) {
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
        Button(onClick = onPedirPermiso) { Text("Abrir Ajustes") }
    }
}

@Composable
private fun ListaApps(apps: List<UsoDeApp>) {
    LazyColumn {
        items(apps, key = { it.app.paquete }) { uso ->
            ListItem(
                headlineContent = { Text(uso.app.nombre) },
                supportingContent = { Text(uso.app.paquete) },
                trailingContent = { Text(formatearDuracion(uso.msHoy)) },
            )
            HorizontalDivider()
        }
    }
}

internal fun formatearDuracion(ms: Long): String {
    val minutos = ms / 60_000
    return when {
        minutos < 1 -> "< 1 min"
        minutos < 60 -> "$minutos min"
        else -> "${minutos / 60} h ${minutos % 60} min"
    }
}

@Preview(showBackground = true)
@Composable
private fun VistaPreviaLista() {
    TemaBlockApp {
        ContenidoApps(
            estado = EstadoApps.Listo(
                listOf(
                    UsoDeApp(AppInstalada("com.instagram.android", "Instagram"), 47 * 60_000L),
                    UsoDeApp(AppInstalada("com.twitter.android", "X"), 12 * 60_000L),
                    UsoDeApp(AppInstalada("com.whatsapp", "WhatsApp"), 0L),
                ),
            ),
            onPedirPermiso = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VistaPreviaSinPermiso() {
    TemaBlockApp { ContenidoApps(estado = EstadoApps.SinPermiso, onPedirPermiso = {}) }
}

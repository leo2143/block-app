package com.leo.blockapp.adapters.input.ui.blocking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.leo.blockapp.adapters.input.ui.theme.BlockAppTheme
import com.leo.blockapp.domain.model.BlockReason

private const val HOME_BUTTON_LABEL = "Ir al inicio"
private const val UNAVAILABLE_DETAIL = "No pudimos comprobar el estado del bloqueo. Seguirá bloqueada."

/** Stateless content of the block screen. Loading and Unavailable keep the app covered with a generic message. */
@Composable
fun BlockingScreen(uiState: BlockingUiState, onGoHome: () -> Unit, modifier: Modifier = Modifier) {
    val heading: String
    val detail: String?
    when (uiState) {
        is BlockingUiState.Blocked -> {
            heading = blockingHeading(uiState.blockName)
            detail = blockingDetail(uiState.reason, uiState.remainingMillis)
        }
        BlockingUiState.Unavailable -> {
            heading = blockingHeading("")
            detail = UNAVAILABLE_DETAIL
        }
        BlockingUiState.Loading, BlockingUiState.Finished -> {
            heading = blockingHeading("")
            detail = null
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = heading,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            if (detail != null) {
                Spacer(Modifier.height(16.dp))
                Text(text = detail, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(32.dp))
            Button(onClick = onGoHome) { Text(HOME_BUTTON_LABEL) }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CooldownPreview() {
    BlockAppTheme {
        BlockingScreen(BlockingUiState.Blocked("Redes", BlockReason.COOLDOWN, 35 * 60_000L), onGoHome = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun DailyCapPreview() {
    BlockAppTheme {
        BlockingScreen(BlockingUiState.Blocked("Redes", BlockReason.DAILY_CAP, 5 * 3_600_000L), onGoHome = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun UnavailablePreview() {
    BlockAppTheme { BlockingScreen(BlockingUiState.Unavailable, onGoHome = {}) }
}

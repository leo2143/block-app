package com.leo.blockapp.adapters.input.ui.permissions

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.leo.blockapp.adapters.input.ui.theme.BlockAppTheme

/**
 * Asks the user to turn on the accessibility service that detects the foreground app. On Android 13+ it also lists
 * the "restricted settings" steps and offers a shortcut to the app info screen.
 */
@Composable
fun AccessibilityRequiredCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AccessibilityRequiredCardContent(
        showRestrictedSettingsSteps = EnforcementPermissionTexts.showsRestrictedSettingsSteps(Build.VERSION.SDK_INT),
        onOpenAccessibilitySettings = { PermissionSettings.openAccessibility(context) },
        onOpenAppInfo = { PermissionSettings.openAppInfo(context) },
        modifier = modifier,
    )
}

@Composable
internal fun AccessibilityRequiredCardContent(
    showRestrictedSettingsSteps: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                EnforcementPermissionTexts.CARD_TITLE,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(EnforcementPermissionTexts.CARD_BODY, style = MaterialTheme.typography.bodyMedium)
            if (showRestrictedSettingsSteps) {
                Text(EnforcementPermissionTexts.RESTRICTED_SETTINGS_INTRO, style = MaterialTheme.typography.bodyMedium)
                EnforcementPermissionTexts.RESTRICTED_SETTINGS_STEPS.forEach { step ->
                    Text(step, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onOpenAppInfo) { Text(EnforcementPermissionTexts.OPEN_APP_INFO) }
            }
            Button(onClick = onOpenAccessibilitySettings) {
                Text(EnforcementPermissionTexts.OPEN_ACCESSIBILITY_SETTINGS)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AccessibilityRequiredCardPreview() {
    BlockAppTheme {
        AccessibilityRequiredCardContent(
            showRestrictedSettingsSteps = true,
            onOpenAccessibilitySettings = {},
            onOpenAppInfo = {},
        )
    }
}

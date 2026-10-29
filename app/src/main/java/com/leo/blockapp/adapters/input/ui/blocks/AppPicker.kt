package com.leo.blockapp.adapters.input.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.leo.blockapp.domain.model.SelectableApp

/**
 * Picker rows for the editor's list. Apps owned by other blocks stay selectable (selecting one triggers the move
 * confirmation on save) and show the owner's name. Rows are not tappable while [enabled] is false (saving).
 */
internal fun LazyListScope.appPickerItems(
    rows: List<SelectableApp>,
    selectedPackages: Set<String>,
    enabled: Boolean,
    onToggle: (packageName: String) -> Unit,
) {
    items(rows, key = { it.packageName }) { app ->
        ListItem(
            modifier = Modifier.clickable(enabled = enabled) { onToggle(app.packageName) },
            leadingContent = {
                Checkbox(checked = app.packageName in selectedPackages, onCheckedChange = null, enabled = enabled)
            },
            headlineContent = { Text(app.label) },
            supportingContent = supportingText(app)?.let { text -> { Text(text) } },
        )
    }
}

private fun supportingText(app: SelectableApp): String? {
    val owner = app.ownerBlock
    return when {
        owner != null -> "En «${owner.name}»"
        !app.installed -> "No instalada"
        else -> null
    }
}

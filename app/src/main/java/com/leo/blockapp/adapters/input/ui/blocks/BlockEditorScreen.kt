package com.leo.blockapp.adapters.input.ui.blocks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leo.blockapp.adapters.input.ui.root.RootSnackbarViewModel

@Composable
fun BlockEditorScreen(
    rootSnackbarViewModel: RootSnackbarViewModel,
    onClose: () -> Unit,
    viewModel: BlockEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnClose by rememberUpdatedState(onClose)

    // Events are only events: if one were dropped the screen could spin forever on CloseWithMessage. The handlers
    // below never suspend, so an event received here is always acted upon.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BlockEditorEvent.ShowError -> rootSnackbarViewModel.post(event.text)
                is BlockEditorEvent.CloseWithMessage -> {
                    rootSnackbarViewModel.post(event.text)
                    currentOnClose()
                }
            }
        }
    }

    val saved = (uiState as? BlockEditorUiState.Ready)?.saved == true
    LaunchedEffect(saved) { if (saved) currentOnClose() }

    // System back and the top bar share one path. The VM shows the discard dialog when it returns false.
    val requestBack = { if (viewModel.onBackRequested()) currentOnClose() }
    BackHandler(onBack = requestBack)

    BlockEditorContent(
        uiState = uiState,
        actions = EditorActions(
            onBack = requestBack,
            onNameChange = viewModel::setNameText,
            onToggleApp = viewModel::toggleApp,
            onSearchChange = viewModel::setSearchQuery,
            onLimitText = viewModel::setLimitText,
            onLimitChip = viewModel::selectLimitMinutes,
            onCooldownText = viewModel::setCooldownText,
            onCooldownChip = viewModel::selectCooldownMinutes,
            onCapEnabled = viewModel::setCapEnabled,
            onCapText = viewModel::setCapText,
            onInactivityEnabled = viewModel::setInactivityEnabled,
            onInactivityText = viewModel::setInactivityText,
            onTouch = viewModel::touchField,
            onSave = viewModel::save,
            onConfirmMove = viewModel::confirmMove,
            onCancelMove = viewModel::cancelMove,
            onKeepEditing = viewModel::dismissDiscardDialog,
            // Pop directly: calling onBackRequested() again would just re-open the dialog.
            onDiscard = { currentOnClose() },
        ),
    )
}

private class EditorActions(
    val onBack: () -> Unit,
    val onNameChange: (String) -> Unit,
    val onToggleApp: (String) -> Unit,
    val onSearchChange: (String) -> Unit,
    val onLimitText: (String) -> Unit,
    val onLimitChip: (Int) -> Unit,
    val onCooldownText: (String) -> Unit,
    val onCooldownChip: (Int) -> Unit,
    val onCapEnabled: (Boolean) -> Unit,
    val onCapText: (String) -> Unit,
    val onInactivityEnabled: (Boolean) -> Unit,
    val onInactivityText: (String) -> Unit,
    val onTouch: (EditorField) -> Unit,
    val onSave: () -> Unit,
    val onConfirmMove: () -> Unit,
    val onCancelMove: () -> Unit,
    val onKeepEditing: () -> Unit,
    val onDiscard: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockEditorContent(uiState: BlockEditorUiState, actions: EditorActions) {
    val ready = uiState as? BlockEditorUiState.Ready
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ready?.isNew == false) "Editar bloqueo" else "Nuevo bloqueo") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
        bottomBar = {
            if (ready != null) {
                Button(
                    onClick = actions.onSave,
                    enabled = !ready.isSaving,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(if (ready.isSaving) "Guardando…" else "Guardar")
                }
            }
        },
    ) { innerPadding ->
        when (ready) {
            null -> Box(Modifier.fillMaxSize().padding(innerPadding)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            else -> {
                EditorForm(ready, actions, Modifier.padding(innerPadding))
                EditorDialogs(ready, actions)
            }
        }
    }
}

@Composable
private fun EditorForm(state: BlockEditorUiState.Ready, actions: EditorActions, modifier: Modifier = Modifier) {
    val inputsEnabled = !state.isSaving
    val pickerRows = AppSearch.pickerList(state.apps, state.searchQuery, state.openedSelection)

    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "name") {
            NameField(state, inputsEnabled, actions)
        }
        item(key = "apps-title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Apps", style = MaterialTheme.typography.titleMedium)
                state.errorsFor(EditorField.APPS).forEach { ErrorText(it) }
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = actions.onSearchChange,
                enabled = inputsEnabled,
                singleLine = true,
                label = { Text("Buscar app") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        appPickerItems(pickerRows, state.selectedPackages, inputsEnabled, actions.onToggleApp)
        item(key = "limit") {
            MinutesChipsField(
                title = "Límite de uso (min)",
                presets = MinutesChips.LIMIT,
                field = state.limit,
                errors = state.errorsFor(EditorField.LIMIT, state.limit),
                enabled = inputsEnabled,
                onChip = actions.onLimitChip,
                onText = actions.onLimitText,
                onTouch = { actions.onTouch(EditorField.LIMIT) },
            )
        }
        item(key = "cooldown") {
            MinutesChipsField(
                title = "Enfriamiento (min)",
                presets = MinutesChips.COOLDOWN,
                field = state.cooldown,
                errors = state.errorsFor(EditorField.COOLDOWN, state.cooldown),
                enabled = inputsEnabled,
                onChip = actions.onCooldownChip,
                onText = actions.onCooldownText,
                onTouch = { actions.onTouch(EditorField.COOLDOWN) },
            )
        }
        item(key = "cap") {
            OptionalNumberField(
                title = "Tope diario de ciclos",
                unit = "ciclos",
                enabledByUser = state.capEnabled,
                field = state.cap,
                errors = state.errorsFor(EditorField.CAP, state.cap),
                enabled = inputsEnabled,
                onToggle = actions.onCapEnabled,
                onText = actions.onCapText,
                onTouch = { actions.onTouch(EditorField.CAP) },
            )
        }
        item(key = "inactivity") {
            OptionalNumberField(
                title = "Reiniciar por inactividad",
                unit = "min",
                enabledByUser = state.inactivityEnabled,
                field = state.inactivity,
                errors = state.errorsFor(EditorField.INACTIVITY, state.inactivity),
                enabled = inputsEnabled,
                onToggle = actions.onInactivityEnabled,
                onText = actions.onInactivityText,
                onTouch = { actions.onTouch(EditorField.INACTIVITY) },
            )
        }
    }
}

@Composable
private fun NameField(state: BlockEditorUiState.Ready, enabled: Boolean, actions: EditorActions) {
    val errors = state.errorsFor(EditorField.NAME)
    OutlinedTextField(
        value = state.nameText,
        onValueChange = actions.onNameChange,
        enabled = enabled,
        singleLine = true,
        label = { Text("Nombre") },
        placeholder = { Text(state.suggestedName.ifEmpty { "Nombre del bloqueo" }) },
        isError = errors.isNotEmpty(),
        supportingText = errors.firstOrNull()?.let { message -> { Text(message) } },
        modifier = Modifier.fillMaxWidth().touchOnBlur { actions.onTouch(EditorField.NAME) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinutesChipsField(
    title: String,
    presets: List<Int>,
    field: MinutesField,
    errors: List<String>,
    enabled: Boolean,
    onChip: (Int) -> Unit,
    onText: (String) -> Unit,
    onTouch: () -> Unit,
) {
    // "Otro" stays selected while the user types, even if the typed number happens to equal a preset.
    var otherChosen by rememberSaveable { mutableStateOf(MinutesChips.isCustom(field, presets)) }
    val showText = otherChosen || MinutesChips.isCustom(field, presets)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { minutes ->
                FilterChip(
                    selected = !showText && field.value == minutes,
                    enabled = enabled,
                    onClick = {
                        otherChosen = false
                        onChip(minutes)
                    },
                    label = { Text(minutes.toString()) },
                )
            }
            FilterChip(
                selected = showText,
                enabled = enabled,
                onClick = { otherChosen = true },
                label = { Text("Otro") },
            )
        }
        if (showText) {
            NumberField(field, errors, enabled, onText, onTouch, label = "Minutos")
        } else {
            errors.forEach { ErrorText(it) }
        }
    }
}

@Composable
private fun OptionalNumberField(
    title: String,
    unit: String,
    enabledByUser: Boolean,
    field: MinutesField,
    errors: List<String>,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onText: (String) -> Unit,
    onTouch: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabledByUser, onCheckedChange = onToggle, enabled = enabled)
        }
        if (enabledByUser) {
            NumberField(field, errors, enabled, onText, onTouch, label = unit.replaceFirstChar { it.uppercase() })
        }
    }
}

@Composable
private fun NumberField(
    field: MinutesField,
    errors: List<String>,
    enabled: Boolean,
    onText: (String) -> Unit,
    onTouch: () -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = field.text,
        onValueChange = onText,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = errors.isNotEmpty(),
        supportingText = errors.firstOrNull()?.let { message -> { Text(message) } },
        modifier = Modifier.fillMaxWidth().touchOnBlur(onTouch),
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun EditorDialogs(state: BlockEditorUiState.Ready, actions: EditorActions) {
    val moves = state.pendingMoves
    if (moves != null) {
        AlertDialog(
            // The dialog cannot be dismissed while the confirmed write is in flight.
            onDismissRequest = { if (!state.isSaving) actions.onCancelMove() },
            title = { Text("Mover apps") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoveMessages.build(moves).forEach { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = actions.onConfirmMove, enabled = !state.isSaving) { Text("Mover") }
            },
            dismissButton = {
                TextButton(onClick = actions.onCancelMove, enabled = !state.isSaving) { Text("Cancelar") }
            },
        )
    }
    // Declared after the move dialog so it stacks on top when both are open.
    if (state.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = actions.onKeepEditing,
            title = { Text("¿Descartar cambios?") },
            confirmButton = { TextButton(onClick = actions.onDiscard) { Text("Descartar") } },
            dismissButton = { TextButton(onClick = actions.onKeepEditing) { Text("Seguir editando") } },
        )
    }
}

/** Violation texts for [field], plus the parse error of [minutes] when it has one. */
private fun BlockEditorUiState.Ready.errorsFor(field: EditorField, minutes: MinutesField? = null): List<String> {
    val parseError = minutes?.error?.let { listOf(ViolationMessages.message(it)) }.orEmpty()
    val violations = visibleViolations.filter { it.field() == field }.map { ViolationMessages.message(it) }
    return parseError + violations
}

/** Calls [onBlur] when the input loses focus after having had it ("leaving the field" touches it). */
@Composable
private fun Modifier.touchOnBlur(onBlur: () -> Unit): Modifier {
    var hadFocus by remember { mutableStateOf(false) }
    val currentOnBlur by rememberUpdatedState(onBlur)
    return onFocusChanged { focus ->
        if (focus.isFocused) {
            hadFocus = true
        } else if (hadFocus) {
            hadFocus = false
            currentOnBlur()
        }
    }
}

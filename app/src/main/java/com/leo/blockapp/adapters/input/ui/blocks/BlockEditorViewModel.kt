package com.leo.blockapp.adapters.input.ui.blocks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockViolation
import com.leo.blockapp.domain.model.SaveBlockResult
import com.leo.blockapp.domain.ports.input.GetBlock
import com.leo.blockapp.domain.ports.input.ListSelectableApps
import com.leo.blockapp.domain.ports.input.ObserveBlockStatuses
import com.leo.blockapp.domain.ports.input.SaveBlock
import com.leo.blockapp.domain.ports.input.ValidateBlock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * Input adapter for the block editor. It only knows input ports.
 *
 * Validation is live: the editor builds a draft with the effective name and asks [ValidateBlock]. A violation is
 * visible once its field is touched or after a Save attempt. Save is enabled except while a save is in flight; tapping
 * it with errors shows all of them and never calls [SaveBlock]. Once saved, further Save taps and edits are ignored.
 * Every user change is mirrored into the [SavedStateHandle] so the draft survives
 * process death.
 */
@HiltViewModel
class BlockEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val getBlock: GetBlock,
    private val listSelectableApps: ListSelectableApps,
    private val validateBlock: ValidateBlock,
    private val saveBlock: SaveBlock,
    private val observeBlockStatuses: ObserveBlockStatuses,
) : ViewModel() {

    private val blockId: String? = savedStateHandle.get<String>(KEY_BLOCK_ID)

    private val _uiState = MutableStateFlow<BlockEditorUiState>(BlockEditorUiState.Loading)
    val uiState: StateFlow<BlockEditorUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<BlockEditorEvent>(Channel.BUFFERED)
    val events: Flow<BlockEditorEvent> = eventChannel.receiveAsFlow()

    /** What the editor looked like when it opened; [onBackRequested] compares the form against it. */
    private var initialSnapshot: Snapshot? = null

    /** Violations reported by SaveBlock itself (race / safety net). Cleared by the next edit. */
    private var domainViolations: Set<BlockViolation> = emptySet()

    /** The cap the block had when the editor opened; null for a new block or one without a cap. */
    private var openedCap: Int? = null

    /** True while the edited block is held by its daily cap (from the status port). */
    private var capHoldActive = false

    init {
        viewModelScope.launch { open() }
        if (blockId != null) viewModelScope.launch { followCapHoldWhileObserved(blockId) }
    }

    /**
     * Follows the block status only while the screen observes [uiState] (plus a grace period for rotation), like
     * `stateIn(WhileSubscribed)` does: a hidden editor must not keep the domain wake-up timer and recomputes alive.
     * The state itself stays a plain [MutableStateFlow] so edits are visible synchronously.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun followCapHoldWhileObserved(id: String) {
        _uiState.subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .transformLatest { observed ->
                if (!observed) delay(STOP_TIMEOUT_MILLIS)
                emit(observed)
            }
            .distinctUntilChanged()
            .collectLatest { observed -> if (observed) followCapHold(id) }
    }

    private suspend fun followCapHold(id: String) {
        observeBlockStatuses().collect { statuses ->
            capHoldActive = statuses[id]?.dailyCapPending == true
            ready()?.let { _uiState.value = recomputed(it) }
        }
    }

    // region Form changes

    fun setNameText(text: String) = edit(EditorField.NAME) { it.copy(nameText = text, nameEditedByUser = true) }

    fun toggleApp(packageName: String) = edit(EditorField.APPS) {
        val selection = LinkedHashSet(it.selectedPackages)
        if (!selection.remove(packageName)) selection.add(packageName)
        it.copy(selectedPackages = selection)
    }

    fun setSearchQuery(query: String) = edit(clearsDomainViolations = false) { it.copy(searchQuery = query) }

    fun setLimitText(text: String) = edit(EditorField.LIMIT) { it.copy(limit = MinutesInput.parse(text)) }

    fun selectLimitMinutes(minutes: Int) = edit(EditorField.LIMIT) { it.copy(limit = MinutesInput.of(minutes)) }

    fun setCooldownText(text: String) = edit(EditorField.COOLDOWN) { it.copy(cooldown = MinutesInput.parse(text)) }

    fun selectCooldownMinutes(minutes: Int) = edit(EditorField.COOLDOWN) { it.copy(cooldown = MinutesInput.of(minutes)) }

    fun setCapEnabled(enabled: Boolean) = edit(EditorField.CAP) { it.copy(capEnabled = enabled) }

    fun setCapText(text: String) = edit(EditorField.CAP) { it.copy(cap = MinutesInput.parse(text)) }

    fun setInactivityEnabled(enabled: Boolean) = edit(EditorField.INACTIVITY) { it.copy(inactivityEnabled = enabled) }

    fun setInactivityText(text: String) = edit(EditorField.INACTIVITY) { it.copy(inactivity = MinutesInput.parse(text)) }

    /** The user left [field]: its violations become visible. Not part of the persisted draft. */
    fun touchField(field: EditorField) {
        val current = ready() ?: return
        if (current.isSaving) return
        _uiState.value = recomputed(current.copy(touchedFields = current.touchedFields + field))
    }

    // endregion

    // region Save flow

    fun save() = submit(confirmedMovePackages = emptySet())

    fun confirmMove() {
        val current = ready() ?: return
        if (current.saved) return
        val conflicts = current.pendingMoves ?: return
        submit(confirmedMovePackages = conflicts.map { it.packageName }.toSet())
    }

    fun cancelMove() {
        val current = ready() ?: return
        if (current.isSaving) return
        _uiState.value = current.copy(pendingMoves = null)
    }

    private fun submit(confirmedMovePackages: Set<String>) {
        val current = ready() ?: return
        if (current.isSaving || current.saved) return

        val attempted = recomputed(current.copy(submitAttempted = true))
        if (hasBlockingErrors(attempted)) {
            _uiState.value = attempted
            return
        }

        // isSaving is set before the coroutine starts, so a second tap can never slip in. pendingMoves stays until
        // a result arrives, so a failed write keeps the move dialog and a second confirmation reaches the guard.
        _uiState.value = attempted.copy(isSaving = true)
        val draft = toDraft(attempted)
        viewModelScope.launch {
            try {
                handleResult(saveBlock(draft, confirmedMovePackages))
            } catch (_: IOException) {
                eventChannel.trySend(BlockEditorEvent.ShowError(WriteFailureMessages.NOT_SAVED))
            } finally {
                ready()?.let { _uiState.value = it.copy(isSaving = false) }
            }
        }
    }

    private fun handleResult(result: SaveBlockResult) {
        val current = ready() ?: return
        when (result) {
            is SaveBlockResult.Saved -> _uiState.value = current.copy(saved = true, pendingMoves = null)
            is SaveBlockResult.AppsInOtherBlocks -> _uiState.value = current.copy(pendingMoves = result.conflicts)
            is SaveBlockResult.Invalid -> {
                domainViolations = result.violations
                _uiState.value = recomputed(current.copy(submitAttempted = true, pendingMoves = null))
            }
        }
    }

    // endregion

    // region Back and discard

    /** True when the screen can leave now; false when the discard dialog is shown instead. */
    fun onBackRequested(): Boolean {
        val current = ready() ?: return true
        if (current.isSaving) return false
        if (current.saved || snapshotOf(current) == initialSnapshot) return true
        _uiState.value = current.copy(showDiscardDialog = true)
        return false
    }

    fun dismissDiscardDialog() {
        val current = ready() ?: return
        _uiState.value = current.copy(showDiscardDialog = false)
    }

    // endregion

    private suspend fun open() {
        val block = blockId?.let { id ->
            getBlock(id) ?: run {
                // Covers both a fresh open and a draft restored after the block was deleted.
                eventChannel.trySend(BlockEditorEvent.CloseWithMessage(BLOCK_GONE))
                return
            }
        }
        val apps = listSelectableApps(blockId)
        openedCap = (block?.rule as? BlockRule.AccumulatedUsageWithCooldown)?.dailyCycleCap
        val opened = if (block != null) readyFrom(block) else newEditorState()
        val withApps = opened.copy(apps = apps)
        initialSnapshot = snapshotOf(recomputed(withApps))
        _uiState.value = recomputed(restoreDraft(withApps))
    }

    private fun newEditorState() = BlockEditorUiState.Ready(
        isNew = true,
        nameText = "",
        nameEditedByUser = false,
        suggestedName = "",
        apps = emptyList(),
        openedSelection = emptyList(),
        selectedPackages = emptySet(),
        searchQuery = "",
        limit = MinutesInput.of(DEFAULT_LIMIT_MINUTES),
        cooldown = MinutesInput.of(DEFAULT_COOLDOWN_MINUTES),
        capEnabled = false,
        cap = MinutesInput.of(DEFAULT_CAP_CYCLES),
        inactivityEnabled = false,
        inactivity = MinutesInput.of(DEFAULT_INACTIVITY_MINUTES),
        visibleViolations = emptySet(),
        touchedFields = emptySet(),
        submitAttempted = false,
        pendingMoves = null,
        isSaving = false,
        showDiscardDialog = false,
        saved = false,
    )

    private fun readyFrom(block: Block): BlockEditorUiState.Ready {
        val rule = block.rule as BlockRule.AccumulatedUsageWithCooldown
        val base = newEditorState()
        return base.copy(
            isNew = false,
            nameText = block.name,
            // A stored name was seen and accepted by the user: never rename it behind their back.
            nameEditedByUser = true,
            openedSelection = block.packageNames.toList(),
            selectedPackages = LinkedHashSet(block.packageNames),
            limit = MinutesInput.of(rule.usageLimitMinutes),
            cooldown = MinutesInput.of(rule.cooldownMinutes),
            capEnabled = rule.dailyCycleCap != null,
            cap = rule.dailyCycleCap?.let(MinutesInput::of) ?: base.cap,
            inactivityEnabled = rule.inactivityResetMinutes != null,
            inactivity = rule.inactivityResetMinutes?.let(MinutesInput::of) ?: base.inactivity,
        )
    }

    // region Draft persistence

    private fun restoreDraft(base: BlockEditorUiState.Ready): BlockEditorUiState.Ready {
        if (!savedStateHandle.contains(KEY_NAME)) return base
        // touchedFields and submitAttempted are intentionally not restored: restored values show no errors
        // until the next interaction or Save.
        return base.copy(
            nameText = savedStateHandle.get<String>(KEY_NAME) ?: base.nameText,
            nameEditedByUser = savedStateHandle.get<Boolean>(KEY_NAME_EDITED) ?: base.nameEditedByUser,
            selectedPackages = savedStateHandle.get<ArrayList<String>>(KEY_PACKAGES)
                ?.let { LinkedHashSet(it) } ?: base.selectedPackages,
            searchQuery = savedStateHandle.get<String>(KEY_SEARCH) ?: base.searchQuery,
            limit = savedStateHandle.get<String>(KEY_LIMIT)?.let(MinutesInput::parse) ?: base.limit,
            cooldown = savedStateHandle.get<String>(KEY_COOLDOWN)?.let(MinutesInput::parse) ?: base.cooldown,
            capEnabled = savedStateHandle.get<Boolean>(KEY_CAP_ENABLED) ?: base.capEnabled,
            cap = savedStateHandle.get<String>(KEY_CAP)?.let(MinutesInput::parse) ?: base.cap,
            inactivityEnabled = savedStateHandle.get<Boolean>(KEY_INACTIVITY_ENABLED) ?: base.inactivityEnabled,
            inactivity = savedStateHandle.get<String>(KEY_INACTIVITY)?.let(MinutesInput::parse) ?: base.inactivity,
        )
    }

    private fun persistDraft(state: BlockEditorUiState.Ready) {
        savedStateHandle[KEY_NAME] = state.nameText
        savedStateHandle[KEY_NAME_EDITED] = state.nameEditedByUser
        savedStateHandle[KEY_PACKAGES] = ArrayList(state.selectedPackages)
        savedStateHandle[KEY_SEARCH] = state.searchQuery
        savedStateHandle[KEY_LIMIT] = state.limit.text
        savedStateHandle[KEY_COOLDOWN] = state.cooldown.text
        savedStateHandle[KEY_CAP_ENABLED] = state.capEnabled
        savedStateHandle[KEY_CAP] = state.cap.text
        savedStateHandle[KEY_INACTIVITY_ENABLED] = state.inactivityEnabled
        savedStateHandle[KEY_INACTIVITY] = state.inactivity.text
    }

    // endregion

    // region Derived state

    private fun ready(): BlockEditorUiState.Ready? = _uiState.value as? BlockEditorUiState.Ready

    private fun edit(
        touch: EditorField? = null,
        clearsDomainViolations: Boolean = true,
        transform: (BlockEditorUiState.Ready) -> BlockEditorUiState.Ready,
    ) {
        val current = ready() ?: return
        if (current.isSaving) return
        if (clearsDomainViolations) domainViolations = emptySet()
        var next = transform(current)
        // Conflicts were computed for the old selection; a changed selection makes them stale.
        if (next.selectedPackages != current.selectedPackages) next = next.copy(pendingMoves = null)
        if (touch != null) next = next.copy(touchedFields = next.touchedFields + touch)
        next = recomputed(next)
        _uiState.value = next
        persistDraft(next)
    }

    /** Re-derives the suggestion, the followed name and the visible violations from the user-editable fields. */
    private fun recomputed(state: BlockEditorUiState.Ready): BlockEditorUiState.Ready {
        val labelsByPackage = state.apps.associate { it.packageName to it.label }
        val suggestion = SuggestedBlockName.from(state.selectedPackages.map { labelsByPackage[it] ?: it })
        val named = state.copy(
            suggestedName = suggestion,
            nameText = if (state.nameEditedByUser) state.nameText else suggestion,
        )
        val live = liveViolations(named)
        val visibleLive = if (named.submitAttempted) live else live.filterTo(mutableSetOf()) { it.field() in named.touchedFields }
        return named.copy(
            visibleViolations = visibleLive + domainViolations,
            capChangeAppliesTomorrow = capChangeAppliesTomorrow(named),
        )
    }

    private fun capChangeAppliesTomorrow(state: BlockEditorUiState.Ready): Boolean {
        val savedCap = openedCap
        if (!capHoldActive || savedCap == null) return false
        if (!state.capEnabled) return true
        val formCap = state.cap.value ?: return false
        return formCap > savedCap
    }

    private fun liveViolations(state: BlockEditorUiState.Ready): Set<BlockViolation> {
        val violations = validateBlock(toDraft(state))
        // A field that does not parse is validated with a placeholder; its range violation is not shown, the field
        // shows its parse error instead.
        return violations.filterTo(mutableSetOf()) { violation ->
            when (violation) {
                BlockViolation.USAGE_LIMIT_OUT_OF_RANGE -> state.limit.error == null
                BlockViolation.COOLDOWN_OUT_OF_RANGE -> state.cooldown.error == null
                BlockViolation.DAILY_CYCLE_CAP_OUT_OF_RANGE -> state.cap.error == null
                BlockViolation.INACTIVITY_RESET_OUT_OF_RANGE -> state.inactivity.error == null
                else -> true
            }
        }
    }

    private fun hasBlockingErrors(state: BlockEditorUiState.Ready): Boolean {
        val parseErrors = listOfNotNull(
            state.limit.error,
            state.cooldown.error,
            state.cap.error.takeIf { state.capEnabled },
            state.inactivity.error.takeIf { state.inactivityEnabled },
        )
        return parseErrors.isNotEmpty() || liveViolations(state).isNotEmpty()
    }

    private fun effectiveName(state: BlockEditorUiState.Ready): String =
        state.nameText.trim().ifBlank { state.suggestedName }

    private fun toDraft(state: BlockEditorUiState.Ready) = BlockDraft(
        id = blockId,
        name = effectiveName(state),
        packageNames = LinkedHashSet(state.selectedPackages),
        rule = BlockRule.AccumulatedUsageWithCooldown(
            usageLimitMinutes = state.limit.value ?: PLACEHOLDER_LIMIT_MINUTES,
            cooldownMinutes = state.cooldown.value ?: PLACEHOLDER_COOLDOWN_MINUTES,
            dailyCycleCap = if (state.capEnabled) state.cap.value ?: PLACEHOLDER_CAP_CYCLES else null,
            inactivityResetMinutes = if (state.inactivityEnabled) {
                state.inactivity.value ?: PLACEHOLDER_INACTIVITY_MINUTES
            } else {
                null
            },
        ),
    )

    /** The semantic content of the form. Search query, touched fields and typed-but-equal text are not part of it. */
    private data class Snapshot(
        val name: String,
        val packages: List<String>,
        val limit: Int?,
        val cooldown: Int?,
        val cap: Int?,
        val inactivity: Int?,
    )

    private fun snapshotOf(state: BlockEditorUiState.Ready) = Snapshot(
        name = effectiveName(state),
        packages = state.selectedPackages.toList(),
        limit = state.limit.value,
        cooldown = state.cooldown.value,
        cap = state.cap.value.takeIf { state.capEnabled },
        inactivity = state.inactivity.value.takeIf { state.inactivityEnabled },
    )

    // endregion

    internal companion object {
        /** How long the status feed outlives the last observer of the state (configuration changes). */
        const val STOP_TIMEOUT_MILLIS = 5_000L

        const val KEY_BLOCK_ID = "blockId"
        const val KEY_NAME = "draft.name"
        const val KEY_NAME_EDITED = "draft.nameEditedByUser"
        const val KEY_PACKAGES = "draft.packages"
        const val KEY_SEARCH = "draft.search"
        const val KEY_LIMIT = "draft.limit"
        const val KEY_COOLDOWN = "draft.cooldown"
        const val KEY_CAP_ENABLED = "draft.capEnabled"
        const val KEY_CAP = "draft.cap"
        const val KEY_INACTIVITY_ENABLED = "draft.inactivityEnabled"
        const val KEY_INACTIVITY = "draft.inactivity"

        const val BLOCK_GONE = "Este bloqueo ya no existe"
        const val CAP_APPLIES_TOMORROW_HINT = "Se aplica desde mañana"

        private const val DEFAULT_LIMIT_MINUTES = 10
        private const val DEFAULT_COOLDOWN_MINUTES = 60

        // Values offered when the user switches a rule on; the spec does not fix them.
        private const val DEFAULT_CAP_CYCLES = 3
        private const val DEFAULT_INACTIVITY_MINUTES = 30

        // In-range stand-ins for fields that do not parse, so only the other fields are judged by ValidateBlock.
        private const val PLACEHOLDER_LIMIT_MINUTES = 1
        private const val PLACEHOLDER_COOLDOWN_MINUTES = 15
        private const val PLACEHOLDER_CAP_CYCLES = 1
        private const val PLACEHOLDER_INACTIVITY_MINUTES = 1
    }
}

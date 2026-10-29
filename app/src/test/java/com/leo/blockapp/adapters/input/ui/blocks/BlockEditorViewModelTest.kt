package com.leo.blockapp.adapters.input.ui.blocks

import androidx.lifecycle.SavedStateHandle
import com.leo.blockapp.adapters.input.ui.MainDispatcherRule
import com.leo.blockapp.domain.blocks.BlockValidator
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockChangeSummary
import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockViolation
import com.leo.blockapp.domain.model.MoveConflict
import com.leo.blockapp.domain.model.SaveBlockResult
import com.leo.blockapp.domain.model.SelectableApp
import com.leo.blockapp.domain.ports.input.GetBlock
import com.leo.blockapp.domain.ports.input.ListSelectableApps
import com.leo.blockapp.domain.ports.input.SaveBlock
import com.leo.blockapp.domain.ports.input.ValidateBlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BlockEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val instagram = SelectableApp("com.instagram", "Instagram", installed = true, ownerBlock = null)
    private val x = SelectableApp("com.x", "X", installed = true, ownerBlock = null)
    private val tiktok = SelectableApp("com.tiktok", "TikTok", installed = true, ownerBlock = null)
    private val allApps = listOf(instagram, x, tiktok)

    private val defaultRule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60)

    private fun existingBlock(
        id: String = "B1",
        name: String = "Mi bloqueo",
        packages: Set<String> = linkedSetOf("com.instagram", "com.x"),
        rule: BlockRule = defaultRule,
    ) = Block(id = id, name = name, packageNames = packages, rule = rule, position = 0)

    private fun savedResult(block: Block = existingBlock()) = SaveBlockResult.Saved(
        block,
        BlockChangeSummary(
            affectedBlockIds = setOf(block.id),
            emptiedBlockIds = emptySet(),
            movedPackages = emptySet(),
            addedPackages = emptyMap(),
            removedPackages = emptyMap(),
        ),
    )

    private class FakeGetBlock(private val blocks: Map<String, Block>) : GetBlock {
        override suspend fun invoke(id: String): Block? = blocks[id]
    }

    private class FakeListSelectableApps(private val apps: List<SelectableApp>) : ListSelectableApps {
        val requestedIds = mutableListOf<String?>()

        override suspend fun invoke(editingBlockId: String?): List<SelectableApp> {
            requestedIds += editingBlockId
            return apps
        }
    }

    private class RealValidateBlock : ValidateBlock {
        override fun invoke(draft: BlockDraft): Set<BlockViolation> = BlockValidator.validate(draft)
    }

    private class FakeSaveBlock(
        var onSave: suspend (BlockDraft, Set<String>) -> SaveBlockResult,
    ) : SaveBlock {
        val calls = mutableListOf<Pair<BlockDraft, Set<String>>>()

        override suspend fun invoke(draft: BlockDraft, confirmedMovePackages: Set<String>): SaveBlockResult {
            calls += draft to confirmedMovePackages
            return onSave(draft, confirmedMovePackages)
        }
    }

    private lateinit var saveBlock: FakeSaveBlock
    private lateinit var listApps: FakeListSelectableApps

    private fun TestScope.newViewModel(
        blockId: String? = null,
        blocks: List<Block> = emptyList(),
        handle: SavedStateHandle = SavedStateHandle(),
        validateBlock: ValidateBlock = RealValidateBlock(),
        onSave: suspend (BlockDraft, Set<String>) -> SaveBlockResult = { _, _ -> savedResult() },
    ): BlockEditorViewModel {
        if (blockId != null) handle["blockId"] = blockId
        saveBlock = FakeSaveBlock(onSave)
        listApps = FakeListSelectableApps(allApps)
        val vm = BlockEditorViewModel(
            savedStateHandle = handle,
            getBlock = FakeGetBlock(blocks.associateBy { it.id }),
            listSelectableApps = listApps,
            validateBlock = validateBlock,
            saveBlock = saveBlock,
        )
        advanceUntilIdle()
        return vm
    }

    private fun BlockEditorViewModel.ready(): BlockEditorUiState.Ready =
        uiState.value as BlockEditorUiState.Ready

    private fun TestScope.collectEvents(vm: BlockEditorViewModel): List<BlockEditorEvent> {
        val events = mutableListOf<BlockEditorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    // region Group A: defaults and validation

    @Test
    fun `a new editor starts with limit 10, cooldown 60 and cap and inactivity off`() = runTest {
        val vm = newViewModel()

        val state = vm.ready()
        assertTrue(state.isNew)
        assertEquals(MinutesInput.of(10), state.limit)
        assertEquals(MinutesInput.of(60), state.cooldown)
        assertFalse(state.capEnabled)
        assertFalse(state.inactivityEnabled)
        assertEquals("", state.nameText)
        assertFalse(state.nameEditedByUser)
        assertTrue(state.selectedPackages.isEmpty())
        assertEquals(allApps, state.apps)
        assertEquals(listOf<String?>(null), listApps.requestedIds)
    }

    @Test
    fun `a pristine editor shows no violations and Save is not blocked`() = runTest {
        val vm = newViewModel()

        val state = vm.ready()
        assertTrue(state.visibleViolations.isEmpty())
        assertTrue(state.touchedFields.isEmpty())
        assertFalse(state.submitAttempted)
        assertFalse(state.isSaving)
    }

    @Test
    fun `picking only apps then Save sends limit 10, cooldown 60 and no cap or inactivity`() = runTest {
        val vm = newViewModel()

        vm.toggleApp("com.instagram")
        vm.save()
        advanceUntilIdle()

        val (draft, confirmed) = saveBlock.calls.single()
        assertEquals(
            BlockDraft(
                id = null,
                name = "Instagram",
                packageNames = linkedSetOf("com.instagram"),
                rule = BlockRule.AccumulatedUsageWithCooldown(10, 60, null, null),
            ),
            draft,
        )
        assertEquals(emptySet<String>(), confirmed)
    }

    @Test
    fun `a blank name with apps selected uses the suggestion and shows no NAME_BLANK`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")
        vm.toggleApp("com.x")

        vm.setNameText("   ")
        vm.save()
        advanceUntilIdle()

        assertFalse(BlockViolation.NAME_BLANK in vm.ready().visibleViolations)
        assertEquals("Instagram y X", saveBlock.calls.single().first.name)
    }

    @Test
    fun `a blank name with no apps shows NAME_BLANK and APPS_EMPTY after a Save attempt and does not save`() = runTest {
        val vm = newViewModel()

        vm.save()
        advanceUntilIdle()

        val state = vm.ready()
        assertTrue(state.submitAttempted)
        assertEquals(setOf(BlockViolation.NAME_BLANK, BlockViolation.APPS_EMPTY), state.visibleViolations)
        assertTrue(saveBlock.calls.isEmpty())
        assertFalse(state.isSaving)
    }

    @Test
    fun `a violation is hidden until its field is touched`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")
        assertTrue(vm.ready().visibleViolations.isEmpty())

        vm.toggleApp("com.instagram")

        assertTrue(BlockViolation.APPS_EMPTY in vm.ready().visibleViolations)
        assertEquals(setOf(EditorField.APPS), vm.ready().touchedFields)
    }

    @Test
    fun `leaving a field touches it`() = runTest {
        val vm = newViewModel()
        assertTrue(vm.ready().visibleViolations.isEmpty())

        vm.touchField(EditorField.APPS)

        assertEquals(setOf(BlockViolation.APPS_EMPTY), vm.ready().visibleViolations)
    }

    @Test
    fun `editing only the limit to 0 shows only USAGE_LIMIT_OUT_OF_RANGE`() = runTest {
        val vm = newViewModel()

        vm.setLimitText("0")

        assertEquals(setOf(BlockViolation.USAGE_LIMIT_OUT_OF_RANGE), vm.ready().visibleViolations)
    }

    @Test
    fun `an unparsable limit shows a MinutesError, filters the range violation and keeps the other violations`() = runTest {
        val vm = newViewModel()

        vm.setLimitText("abc")
        vm.save()
        advanceUntilIdle()

        val state = vm.ready()
        assertEquals(MinutesError.NOT_A_NUMBER, state.limit.error)
        assertEquals(setOf(BlockViolation.NAME_BLANK, BlockViolation.APPS_EMPTY), state.visibleViolations)
    }

    @Test
    fun `an unparsable limit does not hide the range violation of another touched field`() = runTest {
        val vm = newViewModel()

        vm.setLimitText("abc")
        vm.setCooldownText("5")

        assertEquals(setOf(BlockViolation.COOLDOWN_OUT_OF_RANGE), vm.ready().visibleViolations)
    }

    @Test
    fun `Save with errors shows all of them and never calls SaveBlock`() = runTest {
        val vm = newViewModel()
        vm.setLimitText("0")
        vm.setCooldownText("5")
        vm.setCapEnabled(true)
        vm.setCapText("21")
        vm.setInactivityEnabled(true)
        vm.setInactivityText("1441")
        vm.setNameText("x".repeat(41))

        vm.save()
        advanceUntilIdle()

        // Every violation except NAME_BLANK, which cannot coexist with a (too long) non-blank name.
        assertEquals(BlockViolation.entries.toSet() - BlockViolation.NAME_BLANK, vm.ready().visibleViolations)
        assertTrue(saveBlock.calls.isEmpty())
    }

    @Test
    fun `a blank, not a number or overflowing field blocks SaveBlock`() = runTest {
        for (badText in listOf("", "abc", "99999999999")) {
            val vm = newViewModel()
            vm.toggleApp("com.instagram")

            vm.setLimitText(badText)
            vm.save()
            advanceUntilIdle()
            assertTrue("limit '$badText'", saveBlock.calls.isEmpty())

            vm.selectLimitMinutes(10)
            vm.setCooldownText(badText)
            vm.save()
            advanceUntilIdle()
            assertTrue("cooldown '$badText'", saveBlock.calls.isEmpty())

            vm.selectCooldownMinutes(60)
            vm.setCapEnabled(true)
            vm.setCapText(badText)
            vm.save()
            advanceUntilIdle()
            assertTrue("cap '$badText'", saveBlock.calls.isEmpty())

            vm.setCapEnabled(false)
            vm.setInactivityEnabled(true)
            vm.setInactivityText(badText)
            vm.save()
            advanceUntilIdle()
            assertTrue("inactivity '$badText'", saveBlock.calls.isEmpty())
        }
    }

    @Test
    fun `a field parse error is exposed on the field`() = runTest {
        val vm = newViewModel()

        vm.setLimitText("")
        assertEquals(MinutesError.BLANK, vm.ready().limit.error)
        vm.setLimitText("99999999999")
        assertEquals(MinutesError.OVERFLOW, vm.ready().limit.error)
        vm.setCooldownText("x")
        assertEquals(MinutesError.NOT_A_NUMBER, vm.ready().cooldown.error)
    }

    @Test
    fun `cap and inactivity text is parsed only while the toggle is on`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")

        vm.setCapText("abc")
        vm.setInactivityText("abc")
        vm.save()
        advanceUntilIdle()

        val rule = saveBlock.calls.single().first.rule as BlockRule.AccumulatedUsageWithCooldown
        assertNull(rule.dailyCycleCap)
        assertNull(rule.inactivityResetMinutes)
    }

    @Test
    fun `a limit of 2000 parses and the domain range violation is shown`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")

        vm.setLimitText("2000")
        vm.save()
        advanceUntilIdle()

        assertEquals(2000, vm.ready().limit.value)
        assertNull(vm.ready().limit.error)
        assertEquals(setOf(BlockViolation.USAGE_LIMIT_OUT_OF_RANGE), vm.ready().visibleViolations)
        assertTrue(saveBlock.calls.isEmpty())
    }

    @Test
    fun `chips set the minutes and touch the field`() = runTest {
        val vm = newViewModel()

        vm.selectLimitMinutes(30)
        vm.selectCooldownMinutes(90)

        assertEquals(MinutesInput.of(30), vm.ready().limit)
        assertEquals(MinutesInput.of(90), vm.ready().cooldown)
        assertEquals(setOf(EditorField.LIMIT, EditorField.COOLDOWN), vm.ready().touchedFields)
    }

    @Test
    fun `enabled cap and inactivity travel in the rule`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")

        vm.setCapEnabled(true)
        vm.setCapText("5")
        vm.setInactivityEnabled(true)
        vm.setInactivityText("45")
        vm.save()
        advanceUntilIdle()

        assertEquals(
            BlockRule.AccumulatedUsageWithCooldown(10, 60, 5, 45),
            saveBlock.calls.single().first.rule,
        )
    }

    @Test
    fun `turning the cap on starts with a valid value and turning it off clears its violation`() = runTest {
        val vm = newViewModel()

        vm.setCapEnabled(true)
        assertNull(vm.ready().cap.error)
        assertTrue(vm.ready().cap.value != null)

        vm.setCapText("99")
        assertEquals(setOf(BlockViolation.DAILY_CYCLE_CAP_OUT_OF_RANGE), vm.ready().visibleViolations)
        vm.setCapEnabled(false)
        assertTrue(vm.ready().visibleViolations.isEmpty())
    }

    @Test
    fun `a name longer than 40 characters shows NAME_TOO_LONG`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")

        vm.setNameText("x".repeat(41))

        assertEquals(setOf(BlockViolation.NAME_TOO_LONG), vm.ready().visibleViolations)
    }

    // endregion

    // region Group B: names and selection

    @Test
    fun `selection order is kept and drives the suggestion`() = runTest {
        val vm = newViewModel()

        vm.toggleApp("com.tiktok")
        vm.toggleApp("com.instagram")

        assertEquals(listOf("com.tiktok", "com.instagram"), vm.ready().selectedPackages.toList())
        assertEquals("TikTok y Instagram", vm.ready().nameText)
        assertEquals("TikTok y Instagram", vm.ready().suggestedName)
    }

    @Test
    fun `three apps give the N more suggestion`() = runTest {
        val vm = newViewModel()

        vm.toggleApp("com.instagram")
        vm.toggleApp("com.x")
        vm.toggleApp("com.tiktok")

        assertEquals("Instagram, X y 1 más", vm.ready().nameText)
    }

    @Test
    fun `deselecting an app removes it and re-adding puts it last`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")
        vm.toggleApp("com.x")

        vm.toggleApp("com.instagram")
        assertEquals(listOf("com.x"), vm.ready().selectedPackages.toList())
        assertEquals("X", vm.ready().nameText)

        vm.toggleApp("com.instagram")
        assertEquals(listOf("com.x", "com.instagram"), vm.ready().selectedPackages.toList())
    }

    @Test
    fun `the suggestion follows the selection until the name is edited`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")
        assertEquals("Instagram", vm.ready().nameText)

        vm.setNameText("Redes")
        vm.toggleApp("com.x")
        vm.toggleApp("com.tiktok")

        assertEquals("Redes", vm.ready().nameText)
        assertTrue(vm.ready().nameEditedByUser)
        assertEquals("Instagram, X y 1 más", vm.ready().suggestedName)
    }

    @Test
    fun `an existing block opens with its data and never gets renamed when apps change`() = runTest {
        val block = existingBlock(
            name = "Mi bloqueo",
            rule = BlockRule.AccumulatedUsageWithCooldown(25, 45, dailyCycleCap = 3, inactivityResetMinutes = 20),
        )
        val vm = newViewModel(blockId = "B1", blocks = listOf(block))

        val opened = vm.ready()
        assertFalse(opened.isNew)
        assertTrue(opened.nameEditedByUser)
        assertEquals("Mi bloqueo", opened.nameText)
        assertEquals(listOf("com.instagram", "com.x"), opened.selectedPackages.toList())
        assertEquals(listOf("com.instagram", "com.x"), opened.openedSelection)
        assertEquals(MinutesInput.of(25), opened.limit)
        assertEquals(MinutesInput.of(45), opened.cooldown)
        assertTrue(opened.capEnabled)
        assertEquals(MinutesInput.of(3), opened.cap)
        assertTrue(opened.inactivityEnabled)
        assertEquals(MinutesInput.of(20), opened.inactivity)
        assertEquals(listOf<String?>("B1"), listApps.requestedIds)

        vm.toggleApp("com.tiktok")

        assertEquals("Mi bloqueo", vm.ready().nameText)
    }

    @Test
    fun `an existing block with cap and inactivity off opens with both toggles off`() = runTest {
        val vm = newViewModel(blockId = "B1", blocks = listOf(existingBlock()))

        assertFalse(vm.ready().capEnabled)
        assertFalse(vm.ready().inactivityEnabled)
    }

    @Test
    fun `a long suggestion is truncated to 40 characters`() = runTest {
        val long = SelectableApp("com.long", "L".repeat(30), installed = true, ownerBlock = null)
        val other = SelectableApp("com.other", "O".repeat(30), installed = true, ownerBlock = null)
        val vm = newViewModelWithApps(listOf(long, other))

        vm.toggleApp("com.long")
        vm.toggleApp("com.other")

        assertEquals(40, vm.ready().nameText.length)
        assertTrue(vm.ready().nameText.endsWith("…"))
        assertFalse(BlockViolation.NAME_TOO_LONG in vm.ready().visibleViolations)
    }

    @Test
    fun `a stored package without an installed app is named by its package`() = runTest {
        val block = existingBlock(packages = linkedSetOf("com.removed.app"), name = "Viejo")
        val vm = newViewModel(blockId = "B1", blocks = listOf(block))

        vm.setNameText("")

        assertEquals(listOf("com.removed.app"), vm.ready().selectedPackages.toList())
        assertEquals("com.removed.app", vm.ready().suggestedName)
    }

    private fun TestScope.newViewModelWithApps(apps: List<SelectableApp>): BlockEditorViewModel {
        saveBlock = FakeSaveBlock { _, _ -> savedResult() }
        listApps = FakeListSelectableApps(apps)
        val vm = BlockEditorViewModel(
            savedStateHandle = SavedStateHandle(),
            getBlock = FakeGetBlock(emptyMap()),
            listSelectableApps = listApps,
            validateBlock = RealValidateBlock(),
            saveBlock = saveBlock,
        )
        advanceUntilIdle()
        return vm
    }

    // endregion

    // region Group C: save flow

    @Test
    fun `Save sets isSaving synchronously and a second Save is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = newViewModel(onSave = { _, _ ->
            gate.await()
            savedResult()
        })
        vm.toggleApp("com.instagram")

        vm.save()
        assertTrue(vm.ready().isSaving)
        vm.save()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, saveBlock.calls.size)
        assertFalse(vm.ready().isSaving)
        assertTrue(vm.ready().saved)
    }

    @Test
    fun `a double move confirmation calls SaveBlock once more`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var call = 0
        val vm = newViewModel(onSave = { _, _ ->
            call++
            if (call == 1) {
                conflictResult()
            } else {
                gate.await()
                savedResult()
            }
        })
        vm.toggleApp("com.instagram")
        vm.save()
        advanceUntilIdle()

        vm.confirmMove()
        assertTrue(vm.ready().isSaving)
        assertEquals(conflictResult().conflicts, vm.ready().pendingMoves)
        vm.confirmMove()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, saveBlock.calls.size)
        assertFalse(vm.ready().isSaving)
        assertNull(vm.ready().pendingMoves)
    }

    @Test
    fun `Save after a successful save is ignored`() = runTest {
        var call = 0
        val vm = newViewModel(onSave = { _, _ -> if (++call == 1) savedResult() else conflictResult() })
        vm.toggleApp("com.instagram")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.ready().saved)

        vm.save()
        advanceUntilIdle()

        assertEquals(1, saveBlock.calls.size)
        assertNull(vm.ready().pendingMoves)
        assertFalse(vm.ready().isSaving)
    }

    @Test
    fun `edits and touches are ignored while saving`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = newViewModel(onSave = { _, _ ->
            gate.await()
            savedResult()
        })
        vm.toggleApp("com.instagram")
        vm.save()
        val during = vm.ready()

        vm.toggleApp("com.x")
        vm.setNameText("Changed")
        vm.touchField(EditorField.LIMIT)

        assertEquals(during, vm.ready())
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `back while saving does not leave and shows no dialog`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = newViewModel(onSave = { _, _ ->
            gate.await()
            savedResult()
        })
        vm.toggleApp("com.instagram")
        vm.save()

        assertFalse(vm.onBackRequested())
        assertFalse(vm.ready().showDiscardDialog)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a search query change keeps the domain violations`() = runTest {
        val vm = newViewModel(
            validateBlock = object : ValidateBlock {
                override fun invoke(draft: BlockDraft): Set<BlockViolation> = emptySet()
            },
            onSave = { _, _ -> SaveBlockResult.Invalid(setOf(BlockViolation.USAGE_LIMIT_OUT_OF_RANGE)) },
        )
        vm.toggleApp("com.instagram")
        vm.save()
        advanceUntilIdle()

        vm.setSearchQuery("ins")

        assertEquals(setOf(BlockViolation.USAGE_LIMIT_OUT_OF_RANGE), vm.ready().visibleViolations)
    }

    private fun conflictResult() = SaveBlockResult.AppsInOtherBlocks(
        listOf(
            MoveConflict("com.instagram", "Instagram", "B2", "Social", fromBlockWillBeDeleted = false),
            MoveConflict("com.x", "X", "B3", "Otros", fromBlockWillBeDeleted = true),
        ),
    )

    @Test
    fun `apps in other blocks open the move dialog and confirming resubmits with all conflict packages`() = runTest {
        var call = 0
        val vm = newViewModel(onSave = { _, _ -> if (++call == 1) conflictResult() else savedResult() })
        vm.toggleApp("com.instagram")
        vm.toggleApp("com.x")

        vm.save()
        advanceUntilIdle()

        assertEquals(conflictResult().conflicts, vm.ready().pendingMoves)
        assertFalse(vm.ready().isSaving)
        assertFalse(vm.ready().saved)

        vm.confirmMove()
        advanceUntilIdle()

        assertEquals(2, saveBlock.calls.size)
        assertEquals(emptySet<String>(), saveBlock.calls[0].second)
        assertEquals(setOf("com.instagram", "com.x"), saveBlock.calls[1].second)
        assertEquals(saveBlock.calls[0].first, saveBlock.calls[1].first)
        assertNull(vm.ready().pendingMoves)
        assertTrue(vm.ready().saved)
    }

    @Test
    fun `cancelling the move dialog saves nothing`() = runTest {
        val vm = newViewModel(onSave = { _, _ -> conflictResult() })
        vm.toggleApp("com.instagram")
        vm.save()
        advanceUntilIdle()

        vm.cancelMove()
        advanceUntilIdle()

        assertNull(vm.ready().pendingMoves)
        assertEquals(1, saveBlock.calls.size)
        assertFalse(vm.ready().saved)
    }

    @Test
    fun `confirming without a pending move does nothing`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")

        vm.confirmMove()
        advanceUntilIdle()

        assertTrue(saveBlock.calls.isEmpty())
    }

    @Test
    fun `Invalid from the domain is shown like live errors`() = runTest {
        val vm = newViewModel(
            validateBlock = object : ValidateBlock {
                override fun invoke(draft: BlockDraft): Set<BlockViolation> = emptySet()
            },
            onSave = { _, _ -> SaveBlockResult.Invalid(setOf(BlockViolation.USAGE_LIMIT_OUT_OF_RANGE)) },
        )
        vm.toggleApp("com.instagram")

        vm.save()
        advanceUntilIdle()

        assertEquals(setOf(BlockViolation.USAGE_LIMIT_OUT_OF_RANGE), vm.ready().visibleViolations)
        assertTrue(vm.ready().submitAttempted)
        assertFalse(vm.ready().isSaving)
        assertFalse(vm.ready().saved)
    }

    @Test
    fun `domain violations disappear once the user edits again`() = runTest {
        val vm = newViewModel(
            validateBlock = object : ValidateBlock {
                override fun invoke(draft: BlockDraft): Set<BlockViolation> = emptySet()
            },
            onSave = { _, _ -> SaveBlockResult.Invalid(setOf(BlockViolation.USAGE_LIMIT_OUT_OF_RANGE)) },
        )
        vm.toggleApp("com.instagram")
        vm.save()
        advanceUntilIdle()

        vm.selectLimitMinutes(15)

        assertTrue(vm.ready().visibleViolations.isEmpty())
    }

    @Test
    fun `a write failure emits one error event, resets isSaving and allows a retry`() = runTest {
        var fail = true
        val vm = newViewModel(onSave = { _, _ ->
            if (fail) throw IOException("disk")
            savedResult()
        })
        val events = collectEvents(vm)
        vm.toggleApp("com.instagram")

        vm.save()
        advanceUntilIdle()

        assertEquals(listOf<BlockEditorEvent>(BlockEditorEvent.ShowError("No se pudo guardar. Inténtalo de nuevo.")), events)
        assertFalse(vm.ready().isSaving)
        assertFalse(vm.ready().saved)

        fail = false
        vm.save()
        advanceUntilIdle()

        assertEquals(2, saveBlock.calls.size)
        assertTrue(vm.ready().saved)
        assertEquals(1, events.size)
    }

    @Test
    fun `a write failure while confirming a move resets isSaving and keeps the move dialog`() = runTest {
        var call = 0
        val vm = newViewModel(onSave = { _, _ ->
            if (++call == 1) conflictResult() else throw IOException("disk")
        })
        val events = collectEvents(vm)
        vm.toggleApp("com.instagram")
        vm.save()
        advanceUntilIdle()

        vm.confirmMove()
        advanceUntilIdle()

        assertEquals(1, events.size)
        assertFalse(vm.ready().isSaving)
        assertEquals(conflictResult().conflicts, vm.ready().pendingMoves)
    }

    @Test
    fun `a successful save marks saved and emits no event`() = runTest {
        val vm = newViewModel()
        val events = collectEvents(vm)
        vm.toggleApp("com.instagram")

        vm.save()
        advanceUntilIdle()

        assertTrue(vm.ready().saved)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `after a successful save going back does not ask to discard`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.onBackRequested())
        assertFalse(vm.ready().showDiscardDialog)
    }

    @Test
    fun `saving an existing block sends its id`() = runTest {
        val vm = newViewModel(blockId = "B1", blocks = listOf(existingBlock()))
        vm.toggleApp("com.tiktok")

        vm.save()
        advanceUntilIdle()

        val draft = saveBlock.calls.single().first
        assertEquals("B1", draft.id)
        assertEquals("Mi bloqueo", draft.name)
        assertEquals(listOf("com.instagram", "com.x", "com.tiktok"), draft.packageNames.toList())
    }

    // endregion

    // region Group D: draft, discard and missing block

    @Test
    fun `every change is saved to the SavedStateHandle with ordered packages`() = runTest {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle = handle)

        vm.toggleApp("com.tiktok")
        vm.toggleApp("com.instagram")
        vm.setNameText("Redes")
        vm.setLimitText("25")
        vm.setCooldownText("45")
        vm.setCapEnabled(true)
        vm.setCapText("4")
        vm.setInactivityEnabled(true)
        vm.setInactivityText("30")
        vm.setSearchQuery("cam")

        assertEquals(arrayListOf("com.tiktok", "com.instagram"), handle.get<ArrayList<String>>(BlockEditorViewModel.KEY_PACKAGES))
        assertEquals("Redes", handle.get<String>(BlockEditorViewModel.KEY_NAME))
        assertEquals(true, handle.get<Boolean>(BlockEditorViewModel.KEY_NAME_EDITED))
        assertEquals("25", handle.get<String>(BlockEditorViewModel.KEY_LIMIT))
        assertEquals("45", handle.get<String>(BlockEditorViewModel.KEY_COOLDOWN))
        assertEquals(true, handle.get<Boolean>(BlockEditorViewModel.KEY_CAP_ENABLED))
        assertEquals("4", handle.get<String>(BlockEditorViewModel.KEY_CAP))
        assertEquals(true, handle.get<Boolean>(BlockEditorViewModel.KEY_INACTIVITY_ENABLED))
        assertEquals("30", handle.get<String>(BlockEditorViewModel.KEY_INACTIVITY))
        assertEquals("cam", handle.get<String>(BlockEditorViewModel.KEY_SEARCH))
    }

    @Test
    fun `a draft in the SavedStateHandle is restored without touched fields or submit attempt`() = runTest {
        val handle = SavedStateHandle()
        val first = newViewModel(handle = handle)
        first.toggleApp("com.tiktok")
        first.toggleApp("com.instagram")
        first.setNameText("Redes")
        first.setLimitText("0")
        first.setCapEnabled(true)
        first.setCapText("4")
        first.setSearchQuery("ins")
        first.save()
        advanceUntilIdle()
        assertTrue(first.ready().touchedFields.isNotEmpty())
        assertTrue(first.ready().submitAttempted)

        val restored = newViewModel(handle = handle).ready()

        assertEquals(listOf("com.tiktok", "com.instagram"), restored.selectedPackages.toList())
        assertEquals("Redes", restored.nameText)
        assertTrue(restored.nameEditedByUser)
        assertEquals("0", restored.limit.text)
        assertEquals(0, restored.limit.value)
        assertTrue(restored.capEnabled)
        assertEquals("4", restored.cap.text)
        assertEquals("ins", restored.searchQuery)
        assertTrue(restored.touchedFields.isEmpty())
        assertFalse(restored.submitAttempted)
        assertTrue(restored.visibleViolations.isEmpty())
    }

    @Test
    fun `restored errors reappear after the next interaction`() = runTest {
        val handle = SavedStateHandle()
        val first = newViewModel(handle = handle)
        first.setLimitText("0")
        val restored = newViewModel(handle = handle)
        assertTrue(restored.ready().visibleViolations.isEmpty())

        restored.touchField(EditorField.LIMIT)

        assertEquals(setOf(BlockViolation.USAGE_LIMIT_OUT_OF_RANGE), restored.ready().visibleViolations)
    }

    @Test
    fun `a restored draft of an existing block overlays the stored block`() = runTest {
        val handle = SavedStateHandle()
        val first = newViewModel(blockId = "B1", blocks = listOf(existingBlock()), handle = handle)
        first.toggleApp("com.tiktok")
        first.selectLimitMinutes(30)

        val restored = newViewModel(blockId = "B1", blocks = listOf(existingBlock()), handle = handle).ready()

        assertFalse(restored.isNew)
        assertEquals(listOf("com.instagram", "com.x", "com.tiktok"), restored.selectedPackages.toList())
        assertEquals(MinutesInput.of(30), restored.limit)
        assertEquals("Mi bloqueo", restored.nameText)
        assertEquals(listOf("com.instagram", "com.x"), restored.openedSelection)
    }

    @Test
    fun `a pristine editor goes back immediately`() = runTest {
        val vm = newViewModel()

        assertTrue(vm.onBackRequested())
        assertFalse(vm.ready().showDiscardDialog)
    }

    @Test
    fun `back with unsaved changes shows the discard dialog and stays`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")

        assertFalse(vm.onBackRequested())
        assertTrue(vm.ready().showDiscardDialog)

        vm.dismissDiscardDialog()
        assertFalse(vm.ready().showDiscardDialog)
    }

    @Test
    fun `changing a rule value of a new editor is dirty`() = runTest {
        val vm = newViewModel()
        vm.selectLimitMinutes(30)

        assertFalse(vm.onBackRequested())
    }

    @Test
    fun `selecting and deselecting returns to clean`() = runTest {
        val vm = newViewModel()
        vm.toggleApp("com.instagram")
        vm.toggleApp("com.instagram")

        assertTrue(vm.onBackRequested())
    }

    @Test
    fun `search query, touched fields and a rejected Save do not make the editor dirty`() = runTest {
        val vm = newViewModel()

        vm.setSearchQuery("ins")
        vm.touchField(EditorField.NAME)
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.ready().submitAttempted)
        assertTrue(vm.onBackRequested())
    }

    @Test
    fun `rewriting the same name of an existing block is not dirty`() = runTest {
        val vm = newViewModel(blockId = "B1", blocks = listOf(existingBlock()))

        vm.setNameText("Mi bloqueo")
        vm.setNameText("  Mi bloqueo  ")

        assertTrue(vm.ready().nameEditedByUser)
        assertTrue(vm.onBackRequested())
    }

    @Test
    fun `renaming an existing block is dirty`() = runTest {
        val vm = newViewModel(blockId = "B1", blocks = listOf(existingBlock()))

        vm.setNameText("Otro nombre")

        assertFalse(vm.onBackRequested())
    }

    @Test
    fun `a toggle switched on and off again is not dirty`() = runTest {
        val vm = newViewModel(blockId = "B1", blocks = listOf(existingBlock()))

        vm.setCapEnabled(true)
        assertFalse(vm.onBackRequested())
        vm.dismissDiscardDialog()
        vm.setCapEnabled(false)

        assertTrue(vm.onBackRequested())
    }

    @Test
    fun `reordering the selection is dirty`() = runTest {
        val vm = newViewModel(blockId = "B1", blocks = listOf(existingBlock()))

        vm.toggleApp("com.instagram")
        vm.toggleApp("com.instagram")

        assertEquals(listOf("com.x", "com.instagram"), vm.ready().selectedPackages.toList())
        assertFalse(vm.onBackRequested())
    }

    @Test
    fun `a typed rule value that fails to parse is dirty`() = runTest {
        val vm = newViewModel(blockId = "B1", blocks = listOf(existingBlock()))

        vm.setLimitText("abc")

        assertFalse(vm.onBackRequested())
    }

    @Test
    fun `a missing block stays in the loading state`() = runTest {
        val vm = newViewModel(blockId = "B9")

        assertEquals(BlockEditorUiState.Loading, vm.uiState.value)
    }

    @Test
    fun `a missing block emits CloseWithMessage once`() = runTest {
        val vm = newViewModel(blockId = "B9")
        val events = collectEvents(vm)
        advanceUntilIdle()

        assertEquals(listOf<BlockEditorEvent>(BlockEditorEvent.CloseWithMessage("Este bloqueo ya no existe")), events)
    }

    @Test
    fun `a draft restored for a deleted block closes the editor the same way`() = runTest {
        val handle = SavedStateHandle()
        val first = newViewModel(blockId = "B1", blocks = listOf(existingBlock()), handle = handle)
        first.toggleApp("com.tiktok")

        val restored = newViewModel(blockId = "B1", blocks = emptyList(), handle = handle)
        val events = collectEvents(restored)
        advanceUntilIdle()

        assertEquals(BlockEditorUiState.Loading, restored.uiState.value)
        assertEquals(listOf<BlockEditorEvent>(BlockEditorEvent.CloseWithMessage("Este bloqueo ya no existe")), events)
        assertTrue(saveBlock.calls.isEmpty())
    }

    @Test
    fun `a missing block does not list apps`() = runTest {
        newViewModel(blockId = "B9")

        assertTrue(listApps.requestedIds.isEmpty())
    }

    // endregion
}

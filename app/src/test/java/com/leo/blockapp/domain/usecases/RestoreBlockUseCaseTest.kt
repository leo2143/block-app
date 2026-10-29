package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockRepository
import com.leo.blockapp.domain.fakes.FakeIdGenerator
import com.leo.blockapp.domain.fakes.FakeInstalledAppCatalog
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockChangeSummary
import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.BlockReference
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.DeletedBlock
import com.leo.blockapp.domain.model.InstalledApp
import com.leo.blockapp.domain.model.RestoreBlockResult
import com.leo.blockapp.domain.model.SaveBlockResult
import com.leo.blockapp.domain.model.SkippedApp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RestoreBlockUseCaseTest {

    private val instagram = InstalledApp("com.instagram.android", "Instagram")
    private val x = InstalledApp("com.twitter.android", "X")
    private val tiktok = InstalledApp("com.tiktok", "TikTok")
    private val catalog = FakeInstalledAppCatalog(listOf(instagram, x, tiktok))

    private val richRule = BlockRule.AccumulatedUsageWithCooldown(
        usageLimitMinutes = 25,
        cooldownMinutes = 45,
        dailyCycleCap = 3,
        inactivityResetMinutes = 30,
    )

    private fun block(id: String, vararg packageNames: String, enabled: Boolean = true) = Block(
        id = id,
        name = "Block $id",
        packageNames = linkedSetOf(*packageNames),
        rule = richRule,
        enabled = enabled,
    )

    private fun restoreBlockOver(repository: FakeBlockRepository) = RestoreBlockUseCase(repository, catalog)

    private fun deletedAt(index: Int, block: Block) = DeletedBlock(block, index)

    private fun summary(blockId: String, added: Set<String>) = BlockChangeSummary(
        affectedBlockIds = setOf(blockId),
        emptiedBlockIds = emptySet(),
        movedPackages = emptySet(),
        addedPackages = mapOf(blockId to added),
        removedPackages = emptyMap(),
    )

    @Test
    fun `restores the exact block with one write when every app is free`() = runTest {
        val deleted = block("B1", x.packageName, instagram.packageName, enabled = false)
        val other = block("B2", tiktok.packageName)
        val repository = FakeBlockRepository(listOf(other))

        val result = restoreBlockOver(repository)(deletedAt(Int.MAX_VALUE, deleted))

        assertEquals(RestoreBlockResult.Restored(deleted, summary("B1", deleted.packageNames)), result)
        assertEquals(listOf(other, deleted), repository.getBlocks())
        assertEquals(listOf(x.packageName, instagram.packageName), repository.getBlocks().last().packageNames.toList())
        assertEquals(1, repository.writes.size)
        assertEquals(listOf(deleted), repository.writes.single().upserts)
    }

    @Test
    fun `restores only the free apps and reports the skipped one with its owner`() = runTest {
        val deleted = block("B1", instagram.packageName, x.packageName)
        val newOwner = block("B2", instagram.packageName)
        val repository = FakeBlockRepository(listOf(newOwner))

        val result = restoreBlockOver(repository)(deletedAt(Int.MAX_VALUE, deleted))

        val expectedStored = block("B1", x.packageName)
        assertEquals(
            RestoreBlockResult.PartiallyRestored(
                block = expectedStored,
                skippedApps = listOf(SkippedApp(instagram.packageName, "Instagram", BlockReference("B2", "Block B2"))),
                summary = summary("B1", setOf(x.packageName)),
            ),
            result,
        )
        assertEquals(listOf(newOwner, expectedStored), repository.getBlocks())
        assertEquals(1, repository.writes.size)
    }

    @Test
    fun `a partially restored block keeps the original id, name, rule and enabled flag`() = runTest {
        val deleted = block("B1", instagram.packageName, x.packageName, enabled = false)
        val repository = FakeBlockRepository(listOf(block("B2", instagram.packageName)))

        val result = restoreBlockOver(repository)(deletedAt(Int.MAX_VALUE, deleted)) as RestoreBlockResult.PartiallyRestored

        assertEquals(deleted.id, result.block.id)
        assertEquals(deleted.name, result.block.name)
        assertEquals(deleted.rule, result.block.rule)
        assertEquals(false, result.block.enabled)
    }

    @Test
    fun `apps held by a paused block count as taken`() = runTest {
        val deleted = block("B1", instagram.packageName, x.packageName)
        val pausedOwner = block("B2", instagram.packageName, enabled = false)
        val repository = FakeBlockRepository(listOf(pausedOwner))

        val result = restoreBlockOver(repository)(deletedAt(Int.MAX_VALUE, deleted)) as RestoreBlockResult.PartiallyRestored

        assertEquals(
            listOf(SkippedApp(instagram.packageName, "Instagram", BlockReference("B2", "Block B2"))),
            result.skippedApps,
        )
        assertEquals(setOf(x.packageName), result.block.packageNames)
        assertEquals(pausedOwner, repository.getBlocks().first())
    }

    @Test
    fun `skipped apps follow the order of the deleted block and name their own owner`() = runTest {
        val deleted = block("B1", tiktok.packageName, instagram.packageName, x.packageName)
        val repository = FakeBlockRepository(
            listOf(block("B2", x.packageName), block("B3", tiktok.packageName)),
        )

        val result = restoreBlockOver(repository)(deletedAt(Int.MAX_VALUE, deleted)) as RestoreBlockResult.PartiallyRestored

        assertEquals(
            listOf(
                SkippedApp(tiktok.packageName, "TikTok", BlockReference("B3", "Block B3")),
                SkippedApp(x.packageName, "X", BlockReference("B2", "Block B2")),
            ),
            result.skippedApps,
        )
        assertEquals(setOf(instagram.packageName), result.block.packageNames)
    }

    @Test
    fun `does not restore and writes nothing when no app is free`() = runTest {
        val deleted = block("B1", instagram.packageName, x.packageName)
        val owners = listOf(block("B2", instagram.packageName), block("B3", x.packageName, enabled = false))
        val repository = FakeBlockRepository(owners)

        val result = restoreBlockOver(repository)(deletedAt(Int.MAX_VALUE, deleted))

        assertEquals(
            RestoreBlockResult.NotRestored(
                listOf(
                    SkippedApp(instagram.packageName, "Instagram", BlockReference("B2", "Block B2")),
                    SkippedApp(x.packageName, "X", BlockReference("B3", "Block B3")),
                ),
            ),
            result,
        )
        assertEquals(owners, repository.getBlocks())
        assertEquals(0, repository.writes.size)
    }

    @Test
    fun `restoring the same block twice leaves exactly one identical block`() = runTest {
        val deleted = block("B1", instagram.packageName, x.packageName)
        val repository = FakeBlockRepository(listOf(block("B2", tiktok.packageName)))
        val restoreBlock = restoreBlockOver(repository)

        val first = restoreBlock(deletedAt(Int.MAX_VALUE, deleted))
        val second = restoreBlock(deletedAt(Int.MAX_VALUE, deleted))

        assertTrue(first is RestoreBlockResult.Restored)
        assertTrue(second is RestoreBlockResult.Restored)
        assertEquals(1, repository.writes.size)
        assertEquals(listOf("B2", "B1"), repository.getBlocks().map { it.id })
        assertEquals(deleted, repository.getBlocks().single { it.id == "B1" })
    }

    @Test
    fun `restoring a partially restorable block twice is idempotent and never reports itself as owner`() = runTest {
        val deleted = block("B1", instagram.packageName, x.packageName)
        val repository = FakeBlockRepository(listOf(block("B2", instagram.packageName)))
        val restoreBlock = restoreBlockOver(repository)

        restoreBlock(deletedAt(Int.MAX_VALUE, deleted))
        restoreBlock(deletedAt(Int.MAX_VALUE, deleted))

        assertEquals(1, repository.writes.size)
        assertEquals(1, repository.getBlocks().count { it.id == "B1" })
        assertEquals(setOf(x.packageName), repository.getBlocks().single { it.id == "B1" }.packageNames)
    }

    @Test
    fun `deleting then restoring the first, middle or last block recovers the exact original list`() = runTest {
        val original = listOf(
            block("B1", instagram.packageName),
            block("B2", x.packageName),
            block("B3", tiktok.packageName),
        )
        for (id in listOf("B1", "B2", "B3")) {
            val repository = FakeBlockRepository(original)

            val deleted = DeleteBlockUseCase(repository)(id)!!
            restoreBlockOver(repository)(deleted)

            assertEquals("restoring $id", original, repository.getBlocks())
        }
    }

    @Test
    fun `restoring in reverse deletion order recovers the original list`() = runTest {
        val original = listOf(
            block("B1", instagram.packageName),
            block("B2", x.packageName),
            block("B3", tiktok.packageName),
        )
        val repository = FakeBlockRepository(original)
        val deleteBlock = DeleteBlockUseCase(repository)
        val restoreBlock = restoreBlockOver(repository)

        val first = deleteBlock("B1")!!
        val second = deleteBlock("B2")!!
        restoreBlock(second)
        restoreBlock(first)

        assertEquals(original, repository.getBlocks())
    }

    @Test
    fun `restoring in the same order as the deletions clamps positions and keeps every block once`() = runTest {
        val original = listOf(
            block("B1", instagram.packageName),
            block("B2", x.packageName),
            block("B3", tiktok.packageName),
        )
        val repository = FakeBlockRepository(original)
        val deleteBlock = DeleteBlockUseCase(repository)
        val restoreBlock = restoreBlockOver(repository)

        val first = deleteBlock("B1")!!
        val second = deleteBlock("B2")!!
        restoreBlock(first)
        restoreBlock(second)

        assertEquals(listOf("B2", "B1", "B3"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `an index past the end of the list restores at the end`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B2", tiktok.packageName)))

        restoreBlockOver(repository)(deletedAt(5, block("B1", x.packageName)))

        assertEquals(listOf("B2", "B1"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `restoring an id that already exists writes nothing and keeps later edits`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B2", tiktok.packageName)))
        val deleted = deletedAt(0, block("B1", instagram.packageName, x.packageName))
        val restoreBlock = restoreBlockOver(repository)

        restoreBlock(deleted)
        SetBlockEnabledUseCase(repository)("B1", enabled = false)
        val writesBefore = repository.writes.size
        val again = restoreBlock(deleted)

        assertTrue(again is RestoreBlockResult.Restored)
        assertEquals(
            BlockChangeSummary(emptySet(), emptySet(), emptySet(), emptyMap(), emptyMap()),
            (again as RestoreBlockResult.Restored).summary,
        )
        assertEquals(writesBefore, repository.writes.size)
        assertEquals(false, repository.getBlocks().single { it.id == "B1" }.enabled)
    }

    @Test
    fun `a skipped app that is not installed is labelled with its package name`() = runTest {
        val gone = "com.removed.app"
        val deleted = block("B1", gone, x.packageName)
        val repository = FakeBlockRepository(listOf(block("B2", gone)))

        val result = restoreBlockOver(repository)(deletedAt(Int.MAX_VALUE, deleted)) as RestoreBlockResult.PartiallyRestored

        assertEquals(listOf(SkippedApp(gone, gone, BlockReference("B2", "Block B2"))), result.skippedApps)
    }

    @Test
    fun `labels are read once from the catalog before the transaction`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B2", instagram.packageName)))

        restoreBlockOver(repository)(deletedAt(0, block("B1", instagram.packageName, x.packageName)))

        assertEquals(1, catalog.requestCount)
    }

    @Test
    fun `a write failure propagates and leaves the blocks untouched`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B2", tiktok.packageName)))
        repository.writeFailure = IOException("disk full")

        assertThrows(IOException::class.java) {
            runBlocking { restoreBlockOver(repository)(deletedAt(0, block("B1", instagram.packageName))) }
        }

        assertEquals(listOf(block("B2", tiktok.packageName)), repository.getBlocks())
        assertEquals(0, repository.writes.size)
    }

    @Test
    fun `restore racing a save of the same app never leaves the app in two blocks`() = runTest {
        val deleted = block("B1", instagram.packageName, x.packageName)
        val repository = FakeBlockRepository(emptyList(), beforeApply = { yield() })
        val saveBlock = SaveBlockUseCase(repository, FakeIdGenerator(), catalog)
        val restoreBlock = restoreBlockOver(repository)
        val draft = BlockDraft(null, "Redes", linkedSetOf(instagram.packageName), richRule)

        val results = listOf(async { saveBlock(draft) }, async { restoreBlock(deletedAt(Int.MAX_VALUE, deleted)) }).awaitAll()

        assertTrue(results[0] is SaveBlockResult.Saved)
        assertTrue(results[1] is RestoreBlockResult.PartiallyRestored)
        val owners = repository.getBlocks().flatMap { it.packageNames }
        assertEquals(owners.size, owners.toSet().size)
        assertEquals(2, repository.getBlocks().size)
    }
}

package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockRepository
import com.leo.blockapp.domain.fakes.FakeIdGenerator
import com.leo.blockapp.domain.fakes.FakeInstalledAppCatalog
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockChangeSummary
import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.BlockReference
import com.leo.blockapp.domain.model.BlockRule
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

        val result = restoreBlockOver(repository)(deleted)

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

        val result = restoreBlockOver(repository)(deleted)

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

        val result = restoreBlockOver(repository)(deleted) as RestoreBlockResult.PartiallyRestored

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

        val result = restoreBlockOver(repository)(deleted) as RestoreBlockResult.PartiallyRestored

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

        val result = restoreBlockOver(repository)(deleted) as RestoreBlockResult.PartiallyRestored

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

        val result = restoreBlockOver(repository)(deleted)

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

        val first = restoreBlock(deleted)
        val second = restoreBlock(deleted)

        assertEquals(first, second)
        assertTrue(second is RestoreBlockResult.Restored)
        assertEquals(listOf("B2", "B1"), repository.getBlocks().map { it.id })
        assertEquals(deleted, repository.getBlocks().single { it.id == "B1" })
    }

    @Test
    fun `restoring a partially restorable block twice is idempotent and never reports itself as owner`() = runTest {
        val deleted = block("B1", instagram.packageName, x.packageName)
        val repository = FakeBlockRepository(listOf(block("B2", instagram.packageName)))
        val restoreBlock = restoreBlockOver(repository)

        val first = restoreBlock(deleted)
        val second = restoreBlock(deleted)

        assertEquals(first, second)
        assertEquals(1, repository.getBlocks().count { it.id == "B1" })
        assertEquals(setOf(x.packageName), repository.getBlocks().single { it.id == "B1" }.packageNames)
    }

    @Test
    fun `a skipped app that is not installed is labelled with its package name`() = runTest {
        val gone = "com.removed.app"
        val deleted = block("B1", gone, x.packageName)
        val repository = FakeBlockRepository(listOf(block("B2", gone)))

        val result = restoreBlockOver(repository)(deleted) as RestoreBlockResult.PartiallyRestored

        assertEquals(listOf(SkippedApp(gone, gone, BlockReference("B2", "Block B2"))), result.skippedApps)
    }

    @Test
    fun `labels are read once from the catalog before the transaction`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B2", instagram.packageName)))

        restoreBlockOver(repository)(block("B1", instagram.packageName, x.packageName))

        assertEquals(1, catalog.requestCount)
    }

    @Test
    fun `a write failure propagates and leaves the blocks untouched`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B2", tiktok.packageName)))
        repository.writeFailure = IOException("disk full")

        assertThrows(IOException::class.java) {
            runBlocking { restoreBlockOver(repository)(block("B1", instagram.packageName)) }
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

        val results = listOf(async { saveBlock(draft) }, async { restoreBlock(deleted) }).awaitAll()

        assertTrue(results[0] is SaveBlockResult.Saved)
        assertTrue(results[1] is RestoreBlockResult.PartiallyRestored)
        val owners = repository.getBlocks().flatMap { it.packageNames }
        assertEquals(owners.size, owners.toSet().size)
        assertEquals(2, repository.getBlocks().size)
    }
}

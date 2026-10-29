package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockRepository
import com.leo.blockapp.domain.fakes.FakeIdGenerator
import com.leo.blockapp.domain.fakes.FakeInstalledAppCatalog
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockChangeSummary
import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockViolation
import com.leo.blockapp.domain.model.InstalledApp
import com.leo.blockapp.domain.model.MoveConflict
import com.leo.blockapp.domain.model.SaveBlockResult
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveBlockUseCaseTest {

    private val instagram = InstalledApp("com.instagram.android", "Instagram")
    private val x = InstalledApp("com.twitter.android", "X")
    private val tiktok = InstalledApp("com.tiktok", "TikTok")
    private val catalog = FakeInstalledAppCatalog(listOf(instagram, x, tiktok))
    private val idGenerator = FakeIdGenerator()

    private val defaultRule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60)

    private fun block(
        id: String,
        vararg packageNames: String,
        name: String = "Block $id",
        enabled: Boolean = true,
    ) = Block(id, name, linkedSetOf(*packageNames), defaultRule, enabled)

    private fun draft(
        id: String? = null,
        name: String = "Redes",
        packageNames: List<String> = listOf(instagram.packageName),
        rule: BlockRule = defaultRule,
    ) = BlockDraft(id, name, LinkedHashSet(packageNames), rule)

    private fun saveBlockOver(repository: FakeBlockRepository) =
        SaveBlockUseCase(repository, idGenerator, catalog)

    private fun summary(
        affected: Set<String>,
        emptied: Set<String> = emptySet(),
        moved: Set<String> = emptySet(),
        added: Map<String, Set<String>> = emptyMap(),
        removed: Map<String, Set<String>> = emptyMap(),
    ) = BlockChangeSummary(affected, emptied, moved, added, removed)

    @Test
    fun `create stores a trimmed name, enabled, and an id from the generator`() = runTest {
        val repository = FakeBlockRepository()

        val result = saveBlockOver(repository)(draft(name = "  Redes  "))

        val saved = result as SaveBlockResult.Saved
        assertEquals(Block("generated-1", "Redes", linkedSetOf(instagram.packageName), defaultRule, true), saved.block)
        assertEquals(listOf(saved.block), repository.getBlocks())
        assertEquals(1, repository.writes.size)
    }

    @Test
    fun `edit keeps the existing enabled flag`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName, enabled = false)))

        val result = saveBlockOver(repository)(draft(id = "B1", name = "Renamed"))

        val saved = result as SaveBlockResult.Saved
        assertEquals(false, saved.block.enabled)
        assertEquals("Renamed", saved.block.name)
        assertEquals(0, idGenerator.generatedCount)
        assertEquals(listOf(saved.block), repository.getBlocks())
    }

    @Test
    fun `selection order of packages is preserved`() = runTest {
        val repository = FakeBlockRepository()

        val result = saveBlockOver(repository)(
            draft(packageNames = listOf(tiktok.packageName, instagram.packageName, x.packageName)),
        )

        val saved = result as SaveBlockResult.Saved
        assertEquals(
            listOf(tiktok.packageName, instagram.packageName, x.packageName),
            saved.block.packageNames.toList(),
        )
    }

    @Test
    fun `invalid draft reports all violations and writes nothing`() = runTest {
        val repository = FakeBlockRepository()
        val invalidRule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 0, cooldownMinutes = 5)

        val result = saveBlockOver(repository)(draft(name = " ", packageNames = emptyList(), rule = invalidRule))

        assertEquals(
            SaveBlockResult.Invalid(
                setOf(
                    BlockViolation.NAME_BLANK,
                    BlockViolation.APPS_EMPTY,
                    BlockViolation.USAGE_LIMIT_OUT_OF_RANGE,
                    BlockViolation.COOLDOWN_OUT_OF_RANGE,
                ),
            ),
            result,
        )
        assertEquals(0, repository.writes.size)
        assertEquals(0, idGenerator.generatedCount)
    }

    @Test
    fun `invalid is reported before apps in other blocks`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName)))

        val result = saveBlockOver(repository)(draft(name = ""))

        assertEquals(SaveBlockResult.Invalid(setOf(BlockViolation.NAME_BLANK)), result)
    }

    @Test
    fun `app owned by another block conflicts without confirmation and nothing is persisted`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName, "other", name = "Social")))

        val result = saveBlockOver(repository)(draft())

        assertEquals(
            SaveBlockResult.AppsInOtherBlocks(
                listOf(MoveConflict(instagram.packageName, "Instagram", "B1", "Social", fromBlockWillBeDeleted = false)),
            ),
            result,
        )
        assertEquals(0, repository.writes.size)
        assertEquals(0, idGenerator.generatedCount)
    }

    @Test
    fun `a paused owner still conflicts`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName, enabled = false)))

        val result = saveBlockOver(repository)(draft())

        assertTrue(result is SaveBlockResult.AppsInOtherBlocks)
    }

    @Test
    fun `conflict label falls back to the package name when the app is not installed`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "com.removed.app", "other")))

        val result = saveBlockOver(repository)(draft(packageNames = listOf("com.removed.app")))

        val conflicts = (result as SaveBlockResult.AppsInOtherBlocks).conflicts
        assertEquals("com.removed.app", conflicts.single().appLabel)
    }

    @Test
    fun `editing a block keeps its own apps without conflict`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName)))

        val result = saveBlockOver(repository)(draft(id = "B1"))

        assertTrue(result is SaveBlockResult.Saved)
    }

    @Test
    fun `confirmed move strips the app from its source and saves the target`() = runTest {
        val repository = FakeBlockRepository(
            listOf(block("B1", instagram.packageName, x.packageName)),
        )

        val result = saveBlockOver(repository)(draft(), confirmedMovePackages = setOf(instagram.packageName))

        val saved = result as SaveBlockResult.Saved
        assertEquals(
            listOf(
                block("B1", x.packageName),
                saved.block,
            ).sortedBy { it.id },
            repository.getBlocks().sortedBy { it.id },
        )
        assertEquals(1, repository.writes.size)
        assertEquals(
            summary(
                affected = setOf("B1", "generated-1"),
                moved = setOf(instagram.packageName),
                added = mapOf("generated-1" to setOf(instagram.packageName)),
                removed = mapOf("B1" to setOf(instagram.packageName)),
            ),
            saved.summary,
        )
    }

    @Test
    fun `source left without apps is deleted and reported as emptied and affected`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName)))

        val result = saveBlockOver(repository)(draft(), confirmedMovePackages = setOf(instagram.packageName))

        val saved = result as SaveBlockResult.Saved
        assertEquals(listOf(saved.block), repository.getBlocks())
        assertEquals(
            summary(
                affected = setOf("B1", "generated-1"),
                emptied = setOf("B1"),
                moved = setOf(instagram.packageName),
                added = mapOf("generated-1" to setOf(instagram.packageName)),
                removed = mapOf("B1" to setOf(instagram.packageName)),
            ),
            saved.summary,
        )
    }

    @Test
    fun `fromBlockWillBeDeleted is computed over the full set moving from that source`() = runTest {
        val repository = FakeBlockRepository(
            listOf(block("B1", instagram.packageName, x.packageName, name = "Social")),
        )

        val both = saveBlockOver(repository)(draft(packageNames = listOf(instagram.packageName, x.packageName)))
        val onlyOne = saveBlockOver(repository)(draft(packageNames = listOf(instagram.packageName)))

        assertEquals(
            listOf(true, true),
            (both as SaveBlockResult.AppsInOtherBlocks).conflicts.map { it.fromBlockWillBeDeleted },
        )
        assertEquals(
            listOf(false),
            (onlyOne as SaveBlockResult.AppsInOtherBlocks).conflicts.map { it.fromBlockWillBeDeleted },
        )
    }

    @Test
    fun `moving every app of a source deletes it`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName, x.packageName)))

        val result = saveBlockOver(repository)(
            draft(packageNames = listOf(instagram.packageName, x.packageName)),
            confirmedMovePackages = setOf(instagram.packageName, x.packageName),
        )

        val saved = result as SaveBlockResult.Saved
        assertEquals(setOf("B1"), saved.summary.emptiedBlockIds)
        assertEquals(setOf(instagram.packageName, x.packageName), saved.summary.removedPackages.getValue("B1"))
        assertEquals(listOf(saved.block), repository.getBlocks())
    }

    @Test
    fun `moves from two sources are summarized per block`() = runTest {
        val repository = FakeBlockRepository(
            listOf(
                block("B1", instagram.packageName, "keep1"),
                block("B2", x.packageName),
            ),
        )

        val result = saveBlockOver(repository)(
            draft(packageNames = listOf(instagram.packageName, x.packageName)),
            confirmedMovePackages = setOf(instagram.packageName, x.packageName),
        )

        val saved = result as SaveBlockResult.Saved
        assertEquals(setOf("B1", "B2", "generated-1"), saved.summary.affectedBlockIds)
        assertEquals(setOf("B2"), saved.summary.emptiedBlockIds)
        assertEquals(
            mapOf("B1" to setOf(instagram.packageName), "B2" to setOf(x.packageName)),
            saved.summary.removedPackages,
        )
        assertEquals(listOf("B1", "generated-1"), repository.getBlocks().map { it.id }.sorted())
    }

    @Test
    fun `confirmation of a package that does not conflict is ignored`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", x.packageName)))

        val result = saveBlockOver(repository)(
            draft(),
            confirmedMovePackages = setOf(x.packageName, instagram.packageName),
        )

        val saved = result as SaveBlockResult.Saved
        assertEquals(emptySet<String>(), saved.summary.movedPackages)
        assertEquals(setOf(saved.block.id), saved.summary.affectedBlockIds)
        assertEquals(block("B1", x.packageName), repository.getBlocks().first { it.id == "B1" })
    }

    @Test
    fun `unconfirmed conflicts are reported even when other packages are confirmed`() = runTest {
        val repository = FakeBlockRepository(
            listOf(block("B1", instagram.packageName, "k1"), block("B2", x.packageName, "k2")),
        )

        val result = saveBlockOver(repository)(
            draft(packageNames = listOf(instagram.packageName, x.packageName)),
            confirmedMovePackages = setOf(instagram.packageName),
        )

        val conflicts = (result as SaveBlockResult.AppsInOtherBlocks).conflicts
        assertEquals(listOf(x.packageName), conflicts.map { it.packageName })
        assertEquals(0, repository.writes.size)
    }

    @Test
    fun `create summary lists the added packages and no removals`() = runTest {
        val repository = FakeBlockRepository()

        val result = saveBlockOver(repository)(
            draft(packageNames = listOf(instagram.packageName, x.packageName)),
        )

        assertEquals(
            summary(
                affected = setOf("generated-1"),
                added = mapOf("generated-1" to setOf(instagram.packageName, x.packageName)),
            ),
            (result as SaveBlockResult.Saved).summary,
        )
    }

    @Test
    fun `edit summary lists gained and lost packages of the block`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName, x.packageName)))

        val result = saveBlockOver(repository)(
            draft(id = "B1", packageNames = listOf(instagram.packageName, tiktok.packageName)),
        )

        assertEquals(
            summary(
                affected = setOf("B1"),
                added = mapOf("B1" to setOf(tiktok.packageName)),
                removed = mapOf("B1" to setOf(x.packageName)),
            ),
            (result as SaveBlockResult.Saved).summary,
        )
    }

    @Test
    fun `edit without package changes has empty added and removed maps`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName)))

        val result = saveBlockOver(repository)(draft(id = "B1", name = "Renamed"))

        assertEquals(summary(affected = setOf("B1")), (result as SaveBlockResult.Saved).summary)
    }

    @Test
    fun `draft id that does not exist creates keeping that id`() = runTest {
        val repository = FakeBlockRepository()

        val result = saveBlockOver(repository)(draft(id = "B9"))

        val saved = result as SaveBlockResult.Saved
        assertEquals("B9", saved.block.id)
        assertEquals(true, saved.block.enabled)
        assertEquals(0, idGenerator.generatedCount)
        assertEquals(listOf(saved.block), repository.getBlocks())
        assertEquals(mapOf("B9" to setOf(instagram.packageName)), saved.summary.addedPackages)
    }

    @Test
    fun `draft id that does not exist still checks conflicts`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName)))

        val result = saveBlockOver(repository)(draft(id = "B9"))

        assertTrue(result is SaveBlockResult.AppsInOtherBlocks)
    }

    @Test
    fun `concurrent saves of the same app give one Saved and one conflict`() = runTest {
        val repository = FakeBlockRepository()
        val saveBlock = saveBlockOver(repository)

        val first = async { saveBlock(draft(name = "One")) }
        val second = async { saveBlock(draft(name = "Two")) }
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it is SaveBlockResult.Saved })
        assertEquals(1, results.count { it is SaveBlockResult.AppsInOtherBlocks })
        assertEquals(1, repository.getBlocks().size)
    }
}

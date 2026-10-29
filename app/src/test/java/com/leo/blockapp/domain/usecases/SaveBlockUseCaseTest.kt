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
import kotlinx.coroutines.yield
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
    ) = Block(id, name, linkedSetOf(*packageNames), defaultRule, position = id.removePrefix("B").toLongOrNull()?.minus(1) ?: 0, enabled = enabled)

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
        assertEquals(Block("generated-1", "Redes", linkedSetOf(instagram.packageName), defaultRule, position = 0, enabled = true), saved.block)
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
        assertEquals(listOf(block("B1", x.packageName), saved.block), repository.getBlocks())
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
        assertEquals(listOf(block("B1", "keep1"), saved.block), repository.getBlocks())
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
        assertEquals(listOf(block("B1", x.packageName), saved.block), repository.getBlocks())
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
        // yield() inside the repository's read-then-write window forces the two saves to interleave
        // unless the repository serializes them.
        val repository = FakeBlockRepository(beforeApply = { yield() })
        val saveBlock = saveBlockOver(repository)

        val first = async { saveBlock(draft(name = "One")) }
        val second = async { saveBlock(draft(name = "Two")) }
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it is SaveBlockResult.Saved })
        assertEquals(1, results.count { it is SaveBlockResult.AppsInOtherBlocks })
        assertEquals(1, repository.getBlocks().size)
    }

    @Test
    fun `editing a block while confirming moves from two other blocks updates all three`() = runTest {
        val repository = FakeBlockRepository(
            listOf(
                block("B1", instagram.packageName, "old"),
                block("B2", x.packageName, "k"),
                block("B3", tiktok.packageName),
            ),
        )

        val result = saveBlockOver(repository)(
            draft(id = "B1", packageNames = listOf(instagram.packageName, x.packageName, tiktok.packageName)),
            confirmedMovePackages = setOf(x.packageName, tiktok.packageName),
        )

        val saved = result as SaveBlockResult.Saved
        val expectedTarget = block("B1", instagram.packageName, x.packageName, tiktok.packageName, name = "Redes")
        assertEquals(listOf(expectedTarget, block("B2", "k")), repository.getBlocks())
        assertEquals(
            listOf(instagram.packageName, x.packageName, tiktok.packageName),
            repository.getBlocks().first().packageNames.toList(),
        )
        val write = repository.writes.single()
        assertEquals(listOf("B1", "B2"), write.upserts.map { it.id })
        assertEquals(setOf("B3"), write.deletedIds)
        assertEquals(
            summary(
                affected = setOf("B1", "B2", "B3"),
                emptied = setOf("B3"),
                moved = setOf(x.packageName, tiktok.packageName),
                added = mapOf("B1" to setOf(x.packageName, tiktok.packageName)),
                removed = mapOf(
                    "B1" to setOf("old"),
                    "B2" to setOf(x.packageName),
                    "B3" to setOf(tiktok.packageName),
                ),
            ),
            saved.summary,
        )
    }

    @Test
    fun `moving an app out of a paused source keeps the source paused`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName, "k", enabled = false)))

        val result = saveBlockOver(repository)(draft(), confirmedMovePackages = setOf(instagram.packageName))

        val saved = result as SaveBlockResult.Saved
        assertEquals(listOf(block("B1", "k", enabled = false), saved.block), repository.getBlocks())
    }

    @Test
    fun `will-be-deleted is reported per source when sources are mixed`() = runTest {
        val repository = FakeBlockRepository(
            listOf(block("B1", instagram.packageName, "k", name = "One"), block("B2", x.packageName, name = "Two")),
        )

        val result = saveBlockOver(repository)(draft(packageNames = listOf(instagram.packageName, x.packageName)))

        val conflicts = (result as SaveBlockResult.AppsInOtherBlocks).conflicts
        assertEquals(
            listOf(instagram.packageName to false, x.packageName to true),
            conflicts.map { it.packageName to it.fromBlockWillBeDeleted },
        )
    }

    @Test
    fun `a modified source keeps the order of its remaining packages`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", "a", instagram.packageName, "b", "c")))

        saveBlockOver(repository)(draft(), confirmedMovePackages = setOf(instagram.packageName))

        assertEquals(listOf("a", "b", "c"), repository.getBlocks().first().packageNames.toList())
    }

    @Test
    fun `a name of exactly 40 characters with surrounding spaces is saved and 41 is invalid`() = runTest {
        val repository = FakeBlockRepository()
        val saveBlock = saveBlockOver(repository)

        val atLimit = saveBlock(draft(name = "  " + "a".repeat(40) + "  "))
        val overLimit = saveBlock(draft(name = "  " + "a".repeat(41) + "  ", packageNames = listOf(x.packageName)))

        assertEquals("a".repeat(40), (atLimit as SaveBlockResult.Saved).block.name)
        assertEquals(SaveBlockResult.Invalid(setOf(BlockViolation.NAME_TOO_LONG)), overLimit)
        assertEquals(1, repository.getBlocks().size)
    }

    @Test
    fun `apps in other blocks does not consume an id from the generator`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName)))

        saveBlockOver(repository)(draft())

        assertEquals(0, idGenerator.generatedCount)
    }

    @Test
    fun `app labels are fetched once per save`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName, "k")))

        saveBlockOver(repository)(draft(packageNames = listOf(instagram.packageName, x.packageName)))

        assertEquals(1, catalog.requestCount)
    }

    @Test
    fun `a new block is appended with the next position after the highest stored one`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName), block("B2", x.packageName)))

        val saved = saveBlockOver(repository)(draft(packageNames = listOf(tiktok.packageName))) as SaveBlockResult.Saved

        assertEquals(2L, saved.block.position)
        assertEquals(listOf("B1", "B2", "generated-1"), repository.getBlocks().map { it.id })
    }

    @Test
    fun `the next position follows the highest one even when earlier blocks were deleted`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName), block("B3", x.packageName)))

        val saved = saveBlockOver(repository)(draft(packageNames = listOf(tiktok.packageName))) as SaveBlockResult.Saved

        assertEquals(3L, saved.block.position)
    }

    @Test
    fun `a create with an unknown id is appended with the next position`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName)))

        val saved = saveBlockOver(repository)(draft(id = "B9", packageNames = listOf(x.packageName))) as SaveBlockResult.Saved

        assertEquals("B9", saved.block.id)
        assertEquals(1L, saved.block.position)
    }

    @Test
    fun `consecutive creates get increasing positions`() = runTest {
        val repository = FakeBlockRepository()
        val saveBlock = saveBlockOver(repository)

        val first = saveBlock(draft(packageNames = listOf(instagram.packageName))) as SaveBlockResult.Saved
        val second = saveBlock(draft(packageNames = listOf(x.packageName))) as SaveBlockResult.Saved
        val third = saveBlock(draft(packageNames = listOf(tiktok.packageName))) as SaveBlockResult.Saved

        assertEquals(listOf(0L, 1L, 2L), listOf(first, second, third).map { it.block.position })
    }

    @Test
    fun `editing a block keeps its position`() = runTest {
        val repository = FakeBlockRepository(listOf(block("B1", instagram.packageName), block("B2", x.packageName)))

        val saved = saveBlockOver(repository)(
            draft(id = "B1", name = "Renamed", packageNames = listOf(instagram.packageName)),
        ) as SaveBlockResult.Saved

        assertEquals(0L, saved.block.position)
        assertEquals(listOf(0L, 1L), repository.getBlocks().map { it.position })
    }

    @Test
    fun `moving apps out of other blocks does not change their positions`() = runTest {
        val repository = FakeBlockRepository(
            listOf(block("B1", instagram.packageName, "k1"), block("B2", x.packageName, "k2")),
        )

        saveBlockOver(repository)(
            draft(packageNames = listOf(instagram.packageName, x.packageName)),
            confirmedMovePackages = setOf(instagram.packageName, x.packageName),
        )

        assertEquals(listOf(0L, 1L, 2L), repository.getBlocks().map { it.position })
    }
}

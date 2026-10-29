package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeBlockRepository
import com.leo.blockapp.domain.fakes.FakeInstalledAppCatalog
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockReference
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.InstalledApp
import com.leo.blockapp.domain.model.SelectableApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ListSelectableAppsUseCaseTest {

    private val instagram = InstalledApp("com.instagram.android", "Instagram")
    private val x = InstalledApp("com.twitter.android", "X")
    private val tiktok = InstalledApp("com.tiktok", "TikTok")

    private fun block(id: String, vararg packageNames: String, enabled: Boolean = true, position: Long = 0) = Block(
        id = id,
        name = "Block $id",
        packageNames = linkedSetOf(*packageNames),
        rule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 10, cooldownMinutes = 60),
        position = position,
        enabled = enabled,
    )

    private fun listSelectableApps(blocks: List<Block>, vararg installedApps: InstalledApp) =
        ListSelectableAppsUseCase(FakeBlockRepository(blocks), FakeInstalledAppCatalog(installedApps.toList()))

    private fun app(installedApp: InstalledApp, ownerBlock: BlockReference? = null) =
        SelectableApp(installedApp.packageName, installedApp.label, installed = true, ownerBlock = ownerBlock)

    @Test
    fun `lists every launchable app as free when no block exists`() = runTest {
        val result = listSelectableApps(emptyList(), instagram, x)(editingBlockId = null)

        assertEquals(listOf(app(instagram), app(x)), result)
    }

    @Test
    fun `sorts case-insensitively by label`() = runTest {
        val zeta = InstalledApp("pkg.zeta", "zeta")
        val alfa = InstalledApp("pkg.alfa", "Alfa")
        val beta = InstalledApp("pkg.beta", "beta")

        val result = listSelectableApps(emptyList(), zeta, alfa, beta)(editingBlockId = null)

        assertEquals(listOf("Alfa", "beta", "zeta"), result.map { it.label })
    }

    @Test
    fun `marks apps owned by another block, paused owners included`() = runTest {
        val blocks = listOf(
            block("B1", instagram.packageName),
            block("B2", x.packageName, enabled = false),
        )

        val result = listSelectableApps(blocks, instagram, x, tiktok)(editingBlockId = null)

        assertEquals(
            listOf(
                app(instagram, BlockReference("B1", "Block B1")),
                app(tiktok),
                app(x, BlockReference("B2", "Block B2")),
            ),
            result,
        )
    }

    @Test
    fun `apps of the block being edited are not marked as owned`() = runTest {
        val blocks = listOf(block("B1", instagram.packageName, x.packageName), block("B2", tiktok.packageName))

        val result = listSelectableApps(blocks, instagram, x, tiktok)(editingBlockId = "B1")

        assertEquals(
            listOf(app(instagram), app(tiktok, BlockReference("B2", "Block B2")), app(x)),
            result,
        )
    }

    @Test
    fun `packages of the edited block that are not installed are included with the package name as label`() = runTest {
        val blocks = listOf(block("B1", instagram.packageName, "com.removed.app"))

        val result = listSelectableApps(blocks, instagram, x)(editingBlockId = "B1")

        assertEquals(
            listOf(
                SelectableApp("com.removed.app", "com.removed.app", installed = false, ownerBlock = null),
                app(instagram),
                app(x),
            ),
            result,
        )
    }

    @Test
    fun `not installed packages sort among the others by their package name label`() = runTest {
        val blocks = listOf(block("B1", "Zz.gone", "aa.gone"))

        val result = listSelectableApps(blocks, instagram, x)(editingBlockId = "B1")

        assertEquals(listOf("aa.gone", "Instagram", "X", "Zz.gone"), result.map { it.label })
    }

    @Test
    fun `not installed packages of other blocks are not offered`() = runTest {
        val blocks = listOf(block("B1", instagram.packageName), block("B2", "com.removed.app"))

        val result = listSelectableApps(blocks, instagram, x)(editingBlockId = "B1")

        assertEquals(listOf(instagram.packageName, x.packageName), result.map { it.packageName })
    }

    @Test
    fun `a new block editor does not offer not installed packages of existing blocks`() = runTest {
        val blocks = listOf(block("B1", "com.removed.app"))

        val result = listSelectableApps(blocks, instagram)(editingBlockId = null)

        assertEquals(listOf(app(instagram)), result)
    }

    @Test
    fun `an unknown editing id behaves like a new block`() = runTest {
        val blocks = listOf(block("B1", instagram.packageName))

        val result = listSelectableApps(blocks, instagram)(editingBlockId = "B9")

        assertEquals(listOf(app(instagram, BlockReference("B1", "Block B1"))), result)
    }
}

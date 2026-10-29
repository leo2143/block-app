package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.blocks.ownerBlockByPackageName
import com.leo.blockapp.domain.model.BlockReference
import com.leo.blockapp.domain.model.SelectableApp
import com.leo.blockapp.domain.ports.input.ListSelectableApps
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.InstalledAppCatalog

/**
 * Display-only listing for the editor, so a plain read of the blocks is enough (nothing is written back).
 * Apps of the block being edited are never marked as owned, and its packages that are no longer installed
 * stay in the list so the user can remove them.
 */
class ListSelectableAppsUseCase(
    private val blockRepository: BlockRepository,
    private val installedAppCatalog: InstalledAppCatalog,
) : ListSelectableApps {

    override suspend fun invoke(editingBlockId: String?): List<SelectableApp> {
        val launchableApps = installedAppCatalog.getLaunchableApps()
        val blocks = blockRepository.getBlocks()

        val ownerByPackageName = ownerBlockByPackageName(blocks, excludedBlockId = editingBlockId)
            .mapValues { (_, owner) -> BlockReference(owner.id, owner.name) }

        val installedPackageNames = launchableApps.map { it.packageName }.toSet()
        val installedApps = launchableApps.map { app ->
            SelectableApp(app.packageName, app.label, installed = true, ownerBlock = ownerByPackageName[app.packageName])
        }
        val notInstalledApps = blocks
            .filter { it.id == editingBlockId }
            .flatMap { it.packageNames }
            .filter { it !in installedPackageNames }
            .map { packageName -> SelectableApp(packageName, label = packageName, installed = false, ownerBlock = null) }

        return (installedApps + notInstalledApps).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}

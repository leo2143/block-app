package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockChangeSummary
import com.leo.blockapp.domain.model.BlockReference
import com.leo.blockapp.domain.model.RestoreBlockResult
import com.leo.blockapp.domain.model.SkippedApp
import com.leo.blockapp.domain.ports.input.RestoreBlock
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockUpdate
import com.leo.blockapp.domain.ports.output.InstalledAppCatalog

/**
 * Brings back a deleted block with only the apps no other block holds (paused blocks hold theirs too).
 * Blocks with the same id are not treated as owners, so restoring twice is idempotent. App labels are
 * fetched before the transaction because the transform must not suspend. No re-validation: the block was
 * valid when it was deleted.
 */
class RestoreBlockUseCase(
    private val blockRepository: BlockRepository,
    private val installedAppCatalog: InstalledAppCatalog,
) : RestoreBlock {

    override suspend fun invoke(block: Block): RestoreBlockResult {
        val labelsByPackageName = installedAppCatalog.getLaunchableApps().associate { it.packageName to it.label }
        return blockRepository.update { currentBlocks -> decide(block, currentBlocks, labelsByPackageName) }
    }

    private fun decide(
        block: Block,
        currentBlocks: List<Block>,
        labelsByPackageName: Map<String, String>,
    ): BlockUpdate<RestoreBlockResult> {
        val ownerByPackageName = LinkedHashMap<String, Block>()
        currentBlocks.filter { it.id != block.id }.forEach { owner ->
            owner.packageNames.forEach { packageName -> ownerByPackageName.putIfAbsent(packageName, owner) }
        }

        val skippedApps = block.packageNames.mapNotNull { packageName ->
            ownerByPackageName[packageName]?.let { owner ->
                SkippedApp(
                    packageName = packageName,
                    label = labelsByPackageName[packageName] ?: packageName,
                    ownerBlock = BlockReference(owner.id, owner.name),
                )
            }
        }
        val freePackageNames = block.packageNames.filterTo(LinkedHashSet()) { it !in ownerByPackageName }
        if (freePackageNames.isEmpty()) {
            return BlockUpdate(BlockChanges(), RestoreBlockResult.NotRestored(skippedApps))
        }

        val restored = block.copy(packageNames = freePackageNames)
        val summary = BlockChangeSummary(
            affectedBlockIds = setOf(block.id),
            emptiedBlockIds = emptySet(),
            movedPackages = emptySet(),
            addedPackages = mapOf(block.id to freePackageNames),
            removedPackages = emptyMap(),
        )
        val result = if (skippedApps.isEmpty()) {
            RestoreBlockResult.Restored(restored, summary)
        } else {
            RestoreBlockResult.PartiallyRestored(restored, skippedApps, summary)
        }
        return BlockUpdate(BlockChanges(upserts = listOf(restored)), result)
    }
}

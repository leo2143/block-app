package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.blocks.ownerBlockByPackageName
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockChangeSummary
import com.leo.blockapp.domain.model.BlockReference
import com.leo.blockapp.domain.model.DeletedBlock
import com.leo.blockapp.domain.model.RestoreBlockResult
import com.leo.blockapp.domain.model.SkippedApp
import com.leo.blockapp.domain.ports.input.RestoreBlock
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockUpdate
import com.leo.blockapp.domain.ports.output.InstalledAppCatalog

/**
 * Brings back a deleted block at its original position with only the apps no other block holds (paused
 * blocks hold theirs too). The position is `min(index, current size)` inside the same transaction, so undoing
 * deletions in reverse order recovers the original list, and undoing in the same order never fails (positions
 * are clamped). If a block with the same id already exists, nothing is written and the existing block is never
 * overwritten, so a stale or repeated restore is idempotent and keeps later edits. App labels are fetched
 * before the transaction because the transform must not suspend. No re-validation: the block was valid when
 * it was deleted.
 */
class RestoreBlockUseCase(
    private val blockRepository: BlockRepository,
    private val installedAppCatalog: InstalledAppCatalog,
) : RestoreBlock {

    override suspend fun invoke(deletedBlock: DeletedBlock): RestoreBlockResult {
        val labelsByPackageName = installedAppCatalog.getLaunchableApps().associate { it.packageName to it.label }
        return blockRepository.update { currentBlocks -> planRestore(deletedBlock, currentBlocks, labelsByPackageName) }
    }

    private fun planRestore(
        deletedBlock: DeletedBlock,
        currentBlocks: List<Block>,
        labelsByPackageName: Map<String, String>,
    ): BlockUpdate<RestoreBlockResult> {
        val block = deletedBlock.block
        currentBlocks.firstOrNull { it.id == block.id }?.let { return BlockUpdate(BlockChanges(), alreadyPresent(it)) }

        val ownerByPackageName = ownerBlockByPackageName(currentBlocks, excludedBlockId = block.id)

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
        val position = minOf(deletedBlock.index, currentBlocks.size)
        return BlockUpdate(BlockChanges(upserts = listOf(restored), insertAt = mapOf(restored.id to position)), result)
    }

    private fun alreadyPresent(storedBlock: Block) = RestoreBlockResult.Restored(
        storedBlock,
        BlockChangeSummary(
            affectedBlockIds = emptySet(),
            emptiedBlockIds = emptySet(),
            movedPackages = emptySet(),
            addedPackages = emptyMap(),
            removedPackages = emptyMap(),
        ),
    )
}

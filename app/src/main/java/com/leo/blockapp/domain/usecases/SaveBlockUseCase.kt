package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.blocks.BlockValidator
import com.leo.blockapp.domain.blocks.ownerBlockByPackageName
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockChangeSummary
import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.MoveConflict
import com.leo.blockapp.domain.model.SaveBlockResult
import com.leo.blockapp.domain.ports.input.SaveBlock
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockUpdate
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.ports.output.IdGenerator
import com.leo.blockapp.domain.ports.output.InstalledAppCatalog

/**
 * Validates, resolves app conflicts and writes in ONE repository transaction, so two concurrent
 * saves can never both claim the same app. App labels are fetched before the transaction because
 * the transform must not suspend. A new block is appended with position = highest stored position + 1; an
 * edit keeps its position and moving apps never touches the positions of the source blocks. Because the new
 * position is strictly above every stored one, appending keeps the (position, id) order of the stored list.
 */
class SaveBlockUseCase(
    private val blockRepository: BlockRepository,
    private val idGenerator: IdGenerator,
    private val installedAppCatalog: InstalledAppCatalog,
    private val clock: Clock,
) : SaveBlock {

    override suspend fun invoke(draft: BlockDraft, confirmedMovePackages: Set<String>): SaveBlockResult {
        val labelsByPackageName = installedAppCatalog.getLaunchableApps().associate { it.packageName to it.label }
        return blockRepository.update { currentBlocks ->
            decide(draft, confirmedMovePackages, currentBlocks, labelsByPackageName)
        }
    }

    private fun decide(
        draft: BlockDraft,
        confirmedMovePackages: Set<String>,
        currentBlocks: List<Block>,
        labelsByPackageName: Map<String, String>,
    ): BlockUpdate<SaveBlockResult> {
        val violations = BlockValidator.validate(draft)
        if (violations.isNotEmpty()) return rejected(SaveBlockResult.Invalid(violations))

        val otherBlocks = currentBlocks.filter { it.id != draft.id }
        val sourcesById = otherBlocks.associateBy { it.id }
        val ownerByPackageName = ownerBlockByPackageName(currentBlocks, excludedBlockId = draft.id)
        val packagesMovingBySourceId = draft.packageNames
            .mapNotNull { packageName -> ownerByPackageName[packageName]?.let { it.id to packageName } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, packageNames) -> packageNames.toSet() }

        val conflicts = packagesMovingBySourceId.flatMap { (sourceId, movingPackages) ->
            val source = sourcesById.getValue(sourceId)
            val willBeDeleted = (source.packageNames - movingPackages).isEmpty()
            movingPackages
                .filter { it !in confirmedMovePackages }
                .map { packageName ->
                    MoveConflict(
                        packageName = packageName,
                        appLabel = labelsByPackageName[packageName] ?: packageName,
                        fromBlockId = source.id,
                        fromBlockName = source.name,
                        fromBlockWillBeDeleted = willBeDeleted,
                    )
                }
        }
        if (conflicts.isNotEmpty()) return rejected(SaveBlockResult.AppsInOtherBlocks(conflicts))

        val existing = currentBlocks.firstOrNull { it.id == draft.id }
        val addedToTarget = draft.packageNames - (existing?.packageNames ?: emptySet())
        val removedFromTarget = (existing?.packageNames ?: emptySet()) - draft.packageNames
        val savedAtMillis = clock.nowMillis()
        val target = Block(
            id = existing?.id ?: draft.id ?: idGenerator.newId(),
            name = draft.name.trim(),
            packageNames = LinkedHashSet(draft.packageNames),
            rule = draft.rule,
            position = existing?.position ?: nextPosition(currentBlocks),
            enabled = existing?.enabled ?: true,
            joinedAtMillisByPackage = (existing?.joinedAtMillisByPackage.orEmpty() - removedFromTarget) +
                addedToTarget.associateWith { savedAtMillis },
        )

        val emptiedSourceIds = packagesMovingBySourceId
            .filter { (sourceId, moving) -> (sourcesById.getValue(sourceId).packageNames - moving).isEmpty() }
            .keys
        val modifiedSources = packagesMovingBySourceId
            .filterKeys { it !in emptiedSourceIds }
            .map { (sourceId, moving) ->
                val source = sourcesById.getValue(sourceId)
                source.copy(
                    packageNames = source.packageNames - moving,
                    joinedAtMillisByPackage = source.joinedAtMillisByPackage - moving,
                )
            }

        val summary = BlockChangeSummary(
            affectedBlockIds = setOf(target.id) + packagesMovingBySourceId.keys,
            emptiedBlockIds = emptiedSourceIds,
            movedPackages = packagesMovingBySourceId.values.flatten().toSet(),
            addedPackages = mapOf(target.id to addedToTarget).filterValues { it.isNotEmpty() },
            removedPackages = (
                mapOf(target.id to removedFromTarget) + packagesMovingBySourceId
                ).filterValues { it.isNotEmpty() },
        )
        return BlockUpdate(
            changes = BlockChanges(upserts = listOf(target) + modifiedSources, deletedIds = emptiedSourceIds),
            result = SaveBlockResult.Saved(target, summary),
        )
    }

    private fun nextPosition(currentBlocks: List<Block>) = (currentBlocks.maxOfOrNull { it.position } ?: -1L) + 1

    private fun rejected(result: SaveBlockResult) = BlockUpdate(BlockChanges(), result)
}

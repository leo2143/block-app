package com.leo.blockapp.domain.blocks

import com.leo.blockapp.domain.model.Block

/**
 * First block that holds each package, ignoring the block with [excludedBlockId] (the one being edited or
 * restored, which is never its own owner). Paused blocks hold their apps too.
 */
internal fun ownerBlockByPackageName(blocks: List<Block>, excludedBlockId: String?): Map<String, Block> {
    val ownerByPackageName = LinkedHashMap<String, Block>()
    blocks.filter { it.id != excludedBlockId }.forEach { owner ->
        owner.packageNames.forEach { packageName -> ownerByPackageName.putIfAbsent(packageName, owner) }
    }
    return ownerByPackageName
}

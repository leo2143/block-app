package com.leo.blockapp.domain.model

/**
 * A block removed by a delete, anchored to its neighbors at delete time so an undo can put it back where the
 * user saw it. [precedingBlockIds] and [followingBlockIds] are the ids before and after the block, each in list
 * order, read inside the delete transaction so they are exact for that write. A bare index is not used because
 * it drifts when other blocks are deleted or restored in between.
 */
data class DeletedBlock(
    val block: Block,
    val precedingBlockIds: List<String>,
    val followingBlockIds: List<String>,
)

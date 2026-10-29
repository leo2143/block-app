package com.leo.blockapp.domain.model

/**
 * What a write changed, per block. [emptiedBlockIds] is a subset of [affectedBlockIds].
 * Only non-empty entries appear in [addedPackages] and [removedPackages].
 */
data class BlockChangeSummary(
    val affectedBlockIds: Set<String>,
    val emptiedBlockIds: Set<String>,
    val movedPackages: Set<String>,
    val addedPackages: Map<String, Set<String>>,
    val removedPackages: Map<String, Set<String>>,
)

data class BlockReference(
    val id: String,
    val name: String,
)

data class MoveConflict(
    val packageName: String,
    val appLabel: String,
    val fromBlockId: String,
    val fromBlockName: String,
    val fromBlockWillBeDeleted: Boolean,
)

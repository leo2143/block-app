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

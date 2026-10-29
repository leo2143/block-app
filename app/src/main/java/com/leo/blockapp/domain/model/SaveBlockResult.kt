package com.leo.blockapp.domain.model

sealed interface SaveBlockResult {
    data class Saved(val block: Block, val summary: BlockChangeSummary) : SaveBlockResult
    data class Invalid(val violations: Set<BlockViolation>) : SaveBlockResult
    data class AppsInOtherBlocks(val conflicts: List<MoveConflict>) : SaveBlockResult
}

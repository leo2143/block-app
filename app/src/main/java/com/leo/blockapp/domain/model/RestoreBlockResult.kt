package com.leo.blockapp.domain.model

data class SkippedApp(
    val packageName: String,
    val label: String,
    val ownerBlock: BlockReference,
)

sealed interface RestoreBlockResult {
    data class Restored(val block: Block, val summary: BlockChangeSummary) : RestoreBlockResult
    data class PartiallyRestored(
        val block: Block,
        val skippedApps: List<SkippedApp>,
        val summary: BlockChangeSummary,
    ) : RestoreBlockResult
    data class NotRestored(val skippedApps: List<SkippedApp>) : RestoreBlockResult
}

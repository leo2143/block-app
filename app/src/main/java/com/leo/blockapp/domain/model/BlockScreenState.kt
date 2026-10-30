package com.leo.blockapp.domain.model

/** What the block screen shows for one package. */
sealed interface BlockScreenState {
    /** Nothing holds the package any more: the block screen has no reason to stay open. */
    data object Inactive : BlockScreenState

    /**
     * The package is held until [untilMillis] for [reason]. [remainingMillis] is computed when the status is queried
     * so the UI needs no clock. [blockName] is the live block name, or the stored one when the block was deleted; it
     * can be blank, in which case the screen falls back to a generic title.
     */
    data class Active(
        val blockName: String,
        val untilMillis: Long,
        val remainingMillis: Long,
        val reason: BlockReason,
    ) : BlockScreenState
}

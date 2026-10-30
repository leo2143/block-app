package com.leo.blockapp.domain.model

/** What the rules decide for a block app right now. */
sealed interface BlockDecision {
    /** The app may be used for [remainingMillis] more before the limit is reached. */
    data class Allow(val remainingMillis: Long) : BlockDecision

    /** The app stays blocked until [untilMillis] for the given [reason]. */
    data class Blocked(val untilMillis: Long, val reason: BlockReason) : BlockDecision
}

/** Why an app is blocked. */
enum class BlockReason { COOLDOWN, DAILY_CAP }

/** A decision together with the state to persist after taking it. */
data class BlockEvaluation(val decision: BlockDecision, val state: BlockState)

/** The hold that currently keeps a package blocked, owned by [blockId] (named [blockName] when it was stored). */
data class ActiveHold(
    val blockId: String,
    val blockName: String,
    val untilMillis: Long,
    val reason: BlockReason,
)

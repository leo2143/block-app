package com.leo.blockapp.domain.rules

import com.leo.blockapp.domain.model.ActiveHold
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockState

/**
 * Single source of truth for "is this package held right now". Pure: the caller supplies the states and `now`.
 *
 * A hold is active while `now < cooldownEnd` (COOLDOWN, regardless of the stored hold fields) or while
 * `holdUntil > now` (the stored reason, DAILY_CAP when missing). Inconsistent state fails closed: blocking wins.
 * While a cooldown runs it takes precedence even over a longer live DAILY_CAP hold (spec E1/E5): the hold
 * reports COOLDOWN until `cooldownEnd`, and the cap shows only afterwards.
 */
object HoldPolicy {

    /**
     * The hold that keeps [packageName] blocked, across ALL states (including the owner block's own and those of
     * deleted blocks). Winner: greatest `untilMillis`; tie, COOLDOWN before DAILY_CAP; then lowest block id.
     */
    fun activeHold(states: Map<String, BlockState>, packageName: String, nowMillis: Long): ActiveHold? =
        states.entries
            .filter { (_, state) -> packageName in state.holdPackageNames }
            .mapNotNull { (blockId, state) -> holdOf(blockId, state, nowMillis) }
            .minWithOrNull(
                compareByDescending<ActiveHold> { it.untilMillis }
                    .thenBy { it.reason != BlockReason.COOLDOWN }
                    .thenBy { it.blockId },
            )

    /**
     * The active hold of one block, or null when nothing holds it. Fails closed on inconsistent state: a running
     * cooldown holds until `cooldownEnd` whatever the hold fields say, and a live `holdUntil` without a reason is
     * treated as DAILY_CAP.
     */
    fun holdOf(blockId: String, state: BlockState, nowMillis: Long): ActiveHold? {
        val cooldownEndMillis = state.cooldownEndMillis
        if (cooldownEndMillis != null && nowMillis < cooldownEndMillis) {
            return ActiveHold(blockId, state.blockName, cooldownEndMillis, BlockReason.COOLDOWN)
        }
        val holdUntilMillis = state.holdUntilMillis ?: return null
        if (holdUntilMillis <= nowMillis) return null
        return ActiveHold(blockId, state.blockName, holdUntilMillis, state.holdReason ?: BlockReason.DAILY_CAP)
    }
}

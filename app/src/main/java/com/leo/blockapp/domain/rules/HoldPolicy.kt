package com.leo.blockapp.domain.rules

import com.leo.blockapp.domain.model.ActiveHold
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockState

/**
 * Single source of truth for "is this package held right now". Pure: the caller supplies the states and `now`.
 *
 * A hold is active only while `holdUntil > now`. Reason precedence at read time: COOLDOWN while
 * `now < cooldownEnd`, then the stored hold reason (e.g. DAILY_CAP) until `holdUntil`.
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

    /** The active hold of one block, or null when its hold ended, was never set or is malformed. */
    fun holdOf(blockId: String, state: BlockState, nowMillis: Long): ActiveHold? {
        val holdUntilMillis = state.holdUntilMillis ?: return null
        if (holdUntilMillis <= nowMillis) return null
        val holdReason = state.holdReason ?: return null

        val cooldownEndMillis = state.cooldownEndMillis
        return if (cooldownEndMillis != null && nowMillis < cooldownEndMillis) {
            ActiveHold(blockId, state.blockName, cooldownEndMillis, BlockReason.COOLDOWN)
        } else {
            ActiveHold(blockId, state.blockName, holdUntilMillis, holdReason)
        }
    }
}

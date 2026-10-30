package com.leo.blockapp.domain.rules

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockScreenState
import com.leo.blockapp.domain.model.BlockState

/**
 * Read-only view of [HoldPolicy] for the block screen: the same winner the evaluator reports as Restricted, so
 * the screen never disagrees with the enforcement. The block name prefers the live config name and falls back
 * to the one stored with the hold when the block no longer exists.
 */
object BlockStatusResolver {

    fun resolve(
        packageName: String,
        blocks: List<Block>,
        states: Map<String, BlockState>,
        nowMillis: Long,
    ): BlockScreenState {
        val hold = HoldPolicy.activeHold(states, packageName, nowMillis) ?: return BlockScreenState.Inactive
        val blockName = blocks.firstOrNull { it.id == hold.blockId }?.name ?: hold.blockName
        return BlockScreenState.Active(
            blockName = blockName,
            untilMillis = hold.untilMillis,
            remainingMillis = hold.untilMillis - nowMillis,
            reason = hold.reason,
        )
    }
}

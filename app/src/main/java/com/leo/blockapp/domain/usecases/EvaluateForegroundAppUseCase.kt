package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockDecision
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.model.ForegroundAppVerdict
import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.ports.input.EvaluateForegroundApp
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.BlockStateUpdate
import com.leo.blockapp.domain.ports.output.BlockUpdate
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.ports.output.UsageEventSource
import com.leo.blockapp.domain.rules.BlockEvaluator
import com.leo.blockapp.domain.rules.HoldPolicy
import com.leo.blockapp.domain.usage.ForegroundSessions

/**
 * Decides what to do with the app in the foreground, in one atomic [BlockStateRepository.update]:
 * 1. [HoldPolicy] runs first over ALL states, including the owner block's own and those of deleted or paused
 *    blocks, so a hold keeps an app blocked whatever the config says. The evaluator alone is not enough: it only
 *    knows the owner block's state.
 * 2. Otherwise the enabled owner block is evaluated by [BlockEvaluator] and its state is written only if it changed.
 *    A live hold the evaluator did not enforce (inconsistent state) still blocks.
 * 3. Orphan states are garbage collected in the same transaction, but only when an enforced app is evaluated:
 *    [ForegroundAppVerdict.NotEnforced] writes nothing.
 *
 * Usage events are fetched before the transaction (the transform cannot suspend) and the block config is re-read
 * right before it, so a block paused meanwhile is not evaluated. States are only read inside the transaction:
 * storage errors propagate so the caller can fail closed.
 *
 * Detection lag: the foreground app is checked every ~5 s, so usage may exceed the limit by about that much before
 * the cooldown starts (see [BlockEvaluator]).
 */
class EvaluateForegroundAppUseCase(
    private val blockRepository: BlockRepository,
    private val blockStateRepository: BlockStateRepository,
    private val usageEventSource: UsageEventSource,
    private val clock: Clock,
) : EvaluateForegroundApp {

    override suspend fun invoke(packageName: String): ForegroundAppVerdict {
        val nowMillis = clock.nowMillis()
        val startOfDayMillis = clock.startOfDayMillis(nowMillis)
        // DST-safe: 26 h after the start of the day is always inside the next day.
        val startOfNextDayMillis = clock.startOfDayMillis(startOfDayMillis + NEXT_DAY_PROBE_MILLIS)

        var fetched = ownerOf(readBlocks(), packageName)?.let {
            fetchEvents(queryStartMillis(it.rule, startOfDayMillis), nowMillis)
        }
        val blocks = readBlocks()
        val owner = ownerOf(blocks, packageName)
        if (owner != null) {
            val neededFromMillis = queryStartMillis(owner.rule, startOfDayMillis)
            if (fetched == null || neededFromMillis < fetched.fromMillis) {
                fetched = fetchEvents(neededFromMillis, nowMillis)
            }
        }
        val sessions = fetched?.let { ForegroundSessions.fromEvents(it.events, it.fromMillis, nowMillis) }.orEmpty()

        return blockStateRepository.update { states ->
            val hold = HoldPolicy.activeHold(states, packageName, nowMillis)
            if (hold == null && owner == null) {
                return@update BlockStateUpdate(result = ForegroundAppVerdict.NotEnforced)
            }
            val orphanIds = orphanStateIds(states, blocks, nowMillis, startOfDayMillis)
            if (hold != null) {
                return@update BlockStateUpdate(
                    deletedIds = orphanIds,
                    result = ForegroundAppVerdict.Restricted(hold.blockId, hold.untilMillis, hold.reason),
                )
            }
            val ownerBlock = checkNotNull(owner)
            val current = states[ownerBlock.id] ?: BlockState.initial(ownerBlock.name, startOfDayMillis)
            val evaluation = BlockEvaluator.evaluate(
                rule = ownerBlock.rule,
                blockName = ownerBlock.name,
                packageNames = ownerBlock.packageNames,
                joinedAtMillisByPackage = ownerBlock.joinedAtMillisByPackage,
                state = current,
                sessions = sessions,
                nowMillis = nowMillis,
                startOfDayMillis = startOfDayMillis,
                startOfNextDayMillis = startOfNextDayMillis,
            )
            BlockStateUpdate(
                upserts = if (evaluation.state != current) mapOf(ownerBlock.id to evaluation.state) else emptyMap(),
                deletedIds = orphanIds,
                result = verdictOf(ownerBlock, evaluation.decision, evaluation.state, nowMillis),
            )
        }
    }

    private fun verdictOf(
        owner: Block,
        decision: BlockDecision,
        state: BlockState,
        nowMillis: Long,
    ): ForegroundAppVerdict = when (decision) {
        is BlockDecision.Blocked -> ForegroundAppVerdict.Restricted(owner.id, decision.untilMillis, decision.reason)
        is BlockDecision.Allow -> HoldPolicy.holdOf(owner.id, state, nowMillis)
            ?.let { ForegroundAppVerdict.Restricted(owner.id, it.untilMillis, it.reason) }
            ?: ForegroundAppVerdict.Allowed(owner.id, decision.remainingMillis)
    }

    /** A consistent snapshot of the config (a transaction that changes nothing writes nothing). */
    private suspend fun readBlocks(): List<Block> =
        blockRepository.update { current -> BlockUpdate(BlockChanges(), current) }

    private fun ownerOf(blocks: List<Block>, packageName: String): Block? =
        blocks.firstOrNull { it.enabled && packageName in it.packageNames }

    /**
     * Events from the start of the day, extended back by the inactivity lookback only when it is configured (the
     * evaluator ignores anything older than a day before the start of the day). That bounds the query to 48 h.
     */
    private fun queryStartMillis(rule: BlockRule, startOfDayMillis: Long): Long = when (rule) {
        is BlockRule.AccumulatedUsageWithCooldown -> {
            val lookbackMillis = rule.inactivityResetMinutes?.let { minOf(it * MILLIS_PER_MINUTE, MAX_LOOKBACK_MILLIS) }
            startOfDayMillis - (lookbackMillis ?: 0L)
        }
    }

    private suspend fun fetchEvents(fromMillis: Long, toMillis: Long) =
        FetchedEvents(fromMillis, usageEventSource.getEvents(fromMillis, toMillis))

    /** States of blocks that no longer exist, hold nothing and are from a previous day. */
    private fun orphanStateIds(
        states: Map<String, BlockState>,
        blocks: List<Block>,
        nowMillis: Long,
        startOfDayMillis: Long,
    ): Set<String> {
        val blockIds = blocks.map { it.id }.toSet()
        return states
            .filter { (blockId, state) ->
                blockId !in blockIds &&
                    HoldPolicy.holdOf(blockId, state, nowMillis) == null &&
                    state.cyclesDayStartMillis < startOfDayMillis
            }
            .keys
    }

    private class FetchedEvents(val fromMillis: Long, val events: List<UsageEvent>)

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val MAX_LOOKBACK_MILLIS = 24 * 60 * MILLIS_PER_MINUTE
        const val NEXT_DAY_PROBE_MILLIS = 26 * 60 * MILLIS_PER_MINUTE
    }
}

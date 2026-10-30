package com.leo.blockapp.domain.rules

import com.leo.blockapp.domain.model.BlockDecision
import com.leo.blockapp.domain.model.BlockEvaluation
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockRule
import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.usage.ForegroundSession

/**
 * The pure rules of an accumulated-usage block (conventions section 4, steps 1-7): no I/O, no clock reads. Time and
 * the day boundaries are passed in; the returned [BlockEvaluation] carries the state to persist.
 *
 * Detection lag: the foreground app is checked every ~5 s, so usage may exceed the limit by about that much before
 * the block is noticed. The evaluator does not compensate: the cooldown starts when the crossing is detected.
 *
 * Every hold write goes through a max-guard (an active hold is never shortened), and apps added to a block during
 * an active hold are unioned into the hold. [HoldPolicy] reads the same persisted fields, so both agree on what is
 * held.
 */
object BlockEvaluator {

    /**
     * Evaluates the block for one foreground check. [sessions] are the foreground sessions of the block's packages
     * (anything else is ignored), long enough back to cover the inactivity lookback, with a session still open at
     * [nowMillis] ending at [nowMillis].
     */
    @Suppress("LongParameterList")
    fun evaluate(
        rule: BlockRule,
        blockName: String,
        packageNames: Set<String>,
        joinedAtMillisByPackage: Map<String, Long>,
        state: BlockState,
        sessions: List<ForegroundSession>,
        nowMillis: Long,
        startOfDayMillis: Long,
        startOfNextDayMillis: Long,
    ): BlockEvaluation {
        rule as BlockRule.AccumulatedUsageWithCooldown
        val blockSessions = sessions.filter { it.packageName in packageNames }

        // Step 1: new day. Persisted even when a later step returns Blocked.
        var current = normalizeNewDay(state.copy(blockName = blockName), nowMillis, startOfDayMillis)

        // Step 2: a running cooldown blocks; an ended one restarts the cycle at its end.
        val cooldownEndMillis = current.cooldownEndMillis
        if (cooldownEndMillis != null) {
            if (nowMillis < cooldownEndMillis) {
                val decision = BlockDecision.Blocked(cooldownEndMillis, BlockReason.COOLDOWN)
                return finish(decision, current, packageNames, nowMillis)
            }
            current = endCooldown(current, cooldownEndMillis, nowMillis, startOfDayMillis)
        }

        // Step 3: daily cap, also from a live DAILY_CAP hold whatever the current cap says.
        val capReached = rule.dailyCycleCap?.let { current.cyclesToday >= it } == true
        val capHoldActive = current.holdReason == BlockReason.DAILY_CAP && isHoldActive(current, nowMillis)
        if (capReached || capHoldActive) {
            val held = if (capHoldActive) {
                current
            } else {
                writeHold(current, startOfNextDayMillis, BlockReason.DAILY_CAP, nowMillis)
            }
            val decision = BlockDecision.Blocked(held.holdUntilMillis!!, held.holdReason ?: BlockReason.DAILY_CAP)
            return finish(decision, held, packageNames, nowMillis)
        }

        // Step 4: inactivity restarts the cycle at the current session.
        val inactivityMinutes = rule.inactivityResetMinutes
        if (inactivityMinutes != null) {
            current = restartAfterInactivity(
                state = current,
                blockSessions = mergeSessions(blockSessions),
                inactivityMillis = inactivityMinutes * MILLIS_PER_MINUTE,
                nowMillis = nowMillis,
                lookbackFloorMillis = startOfDayMillis - LOOKBACK_CAP_MILLIS,
            )
        }

        // Steps 5-7: measure, then start a cooldown at the limit or allow what is left.
        val limitMillis = rule.usageLimitMinutes * MILLIS_PER_MINUTE
        val usageMillis = measureUsage(current, blockSessions, joinedAtMillisByPackage, nowMillis, startOfDayMillis)
        if (usageMillis >= limitMillis) {
            val cooldownStarted = startCooldown(current, rule, nowMillis, startOfNextDayMillis)
            val decision = BlockDecision.Blocked(cooldownStarted.cooldownEndMillis!!, BlockReason.COOLDOWN)
            return finish(decision, cooldownStarted, packageNames, nowMillis)
        }
        return finish(BlockDecision.Allow(limitMillis - usageMillis), current, packageNames, nowMillis)
    }

    private fun normalizeNewDay(state: BlockState, nowMillis: Long, startOfDayMillis: Long): BlockState {
        if (state.cyclesDayStartMillis == startOfDayMillis) return state
        val cycleStartMillis = maxOf(
            state.cycleStartMillis,
            state.cooldownEndMillis ?: startOfDayMillis,
            startOfDayMillis,
        )
        return clearExpiredHold(
            state.copy(cyclesToday = 0, cyclesDayStartMillis = startOfDayMillis, cycleStartMillis = cycleStartMillis),
            nowMillis,
        )
    }

    private fun endCooldown(
        state: BlockState,
        cooldownEndMillis: Long,
        nowMillis: Long,
        startOfDayMillis: Long,
    ): BlockState = clearExpiredHold(
        state.copy(
            cycleStartMillis = maxOf(state.cycleStartMillis, cooldownEndMillis, startOfDayMillis),
            cooldownEndMillis = null,
        ),
        nowMillis,
    )

    private fun isHoldActive(state: BlockState, nowMillis: Long): Boolean =
        state.holdUntilMillis?.let { it > nowMillis } == true

    private fun clearExpiredHold(state: BlockState, nowMillis: Long): BlockState {
        if (state.holdUntilMillis == null || isHoldActive(state, nowMillis)) return state
        return state.copy(holdUntilMillis = null, holdReason = null, holdPackageNames = emptySet())
    }

    /** Max-guard: an active hold that outlasts [untilMillis] is kept, so a hold write never shortens it. */
    private fun writeHold(state: BlockState, untilMillis: Long, reason: BlockReason, nowMillis: Long): BlockState {
        val existingUntilMillis = state.holdUntilMillis
        val outlasts = existingUntilMillis != null && isHoldActive(state, nowMillis) && existingUntilMillis > untilMillis
        return if (outlasts) state else state.copy(holdUntilMillis = untilMillis, holdReason = reason)
    }

    /** Sessions as one timeline: overlapping or touching sessions (of any package of the block) become one. */
    private fun mergeSessions(blockSessions: List<ForegroundSession>): List<MergedSession> {
        val merged = mutableListOf<MergedSession>()
        for (session in blockSessions.sortedBy { it.startMillis }) {
            val last = merged.lastOrNull()
            if (last != null && session.startMillis <= last.endMillis) {
                merged[merged.lastIndex] = last.copy(endMillis = maxOf(last.endMillis, session.endMillis))
            } else {
                merged += MergedSession(session.startMillis, session.endMillis)
            }
        }
        return merged
    }

    /**
     * Moves the cycle start to the current session when the gap since the previous exit is at least the window.
     * No previous exit within the lookback counts as inactive, and nothing is excluded from the gap (held time
     * counts). The current session is the one still open at [nowMillis]; when none is open yet the app has just come
     * to the foreground and its session starts now. The cycle start only moves forward.
     */
    private fun restartAfterInactivity(
        state: BlockState,
        blockSessions: List<MergedSession>,
        inactivityMillis: Long,
        nowMillis: Long,
        lookbackFloorMillis: Long,
    ): BlockState {
        val openSession = blockSessions.lastOrNull()?.takeIf { it.endMillis >= nowMillis }
        val currentStartMillis = openSession?.startMillis ?: nowMillis
        val previousSession = if (openSession != null) {
            blockSessions.getOrNull(blockSessions.lastIndex - 1)
        } else {
            blockSessions.lastOrNull()
        }
        val previousExitMillis = previousSession?.endMillis?.takeIf { it >= lookbackFloorMillis }
        val inactive = previousExitMillis == null || currentStartMillis - previousExitMillis >= inactivityMillis
        if (!inactive) return state
        return state.copy(cycleStartMillis = maxOf(state.cycleStartMillis, currentStartMillis))
    }

    /** Foreground time of the block's packages, each clipped to `[max(cycleStart, joinedAt, startOfDay), now]`. */
    private fun measureUsage(
        state: BlockState,
        blockSessions: List<ForegroundSession>,
        joinedAtMillisByPackage: Map<String, Long>,
        nowMillis: Long,
        startOfDayMillis: Long,
    ): Long = blockSessions.sumOf { session ->
        val countedFromMillis = maxOf(
            state.cycleStartMillis,
            joinedAtMillisByPackage[session.packageName] ?: state.cycleStartMillis,
            startOfDayMillis,
        )
        maxOf(0L, minOf(session.endMillis, nowMillis) - maxOf(session.startMillis, countedFromMillis))
    }

    /**
     * Starts the cooldown and counts the cycle. The hold is the cooldown until it ends, or, when this cycle reaches
     * the daily cap before the cooldown crosses into the next day, a DAILY_CAP floor until the next day (the
     * cooldown reads first while it runs). Both writes go through the max-guard.
     */
    private fun startCooldown(
        state: BlockState,
        rule: BlockRule.AccumulatedUsageWithCooldown,
        nowMillis: Long,
        startOfNextDayMillis: Long,
    ): BlockState {
        val cooldownEndMillis = nowMillis + rule.cooldownMinutes * MILLIS_PER_MINUTE
        val cyclesToday = state.cyclesToday + 1
        val started = state.copy(cooldownEndMillis = cooldownEndMillis, cyclesToday = cyclesToday)
        val capReached = rule.dailyCycleCap?.let { cyclesToday >= it } == true
        return if (capReached && cooldownEndMillis < startOfNextDayMillis) {
            writeHold(started, startOfNextDayMillis, BlockReason.DAILY_CAP, nowMillis)
        } else {
            writeHold(started, cooldownEndMillis, BlockReason.COOLDOWN, nowMillis)
        }
    }

    /** Apps added to the block during an active hold join it, so they stay held when the rules are edited. */
    private fun finish(
        decision: BlockDecision,
        state: BlockState,
        packageNames: Set<String>,
        nowMillis: Long,
    ): BlockEvaluation {
        val held = if (isHoldActive(state, nowMillis)) {
            state.copy(holdPackageNames = state.holdPackageNames + packageNames)
        } else {
            state
        }
        return BlockEvaluation(decision, held)
    }

    private data class MergedSession(val startMillis: Long, val endMillis: Long)

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val LOOKBACK_CAP_MILLIS = 24 * 60 * MILLIS_PER_MINUTE
}

package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.ActiveHold
import com.leo.blockapp.domain.model.BlockListStatus
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.ports.input.ObserveBlockStatuses
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.rules.HoldPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * Status lines from the same [HoldPolicy] that drives enforcement. For each emission of the stored states it emits
 * the lines for "now", then sleeps until the earliest active hold ends and emits again (a cooldown that ends hands
 * over to a longer cap hold, or to nothing). A new state emission cancels the pending wake-up. There is no polling:
 * with no active hold it just waits for the next state change.
 *
 * It emits nothing while the states cannot be read, so a storage failure never turns into "everything is free".
 */
class ObserveBlockStatusesUseCase(
    private val blockStateRepository: BlockStateRepository,
    private val clock: Clock,
) : ObserveBlockStatuses {

    override fun invoke(): Flow<Map<String, BlockListStatus>> =
        blockStateRepository.observeStates()
            .transformLatest { states ->
                while (true) {
                    val nowMillis = clock.nowMillis()
                    val holds = states.mapNotNull { (blockId, state) -> HoldPolicy.holdOf(blockId, state, nowMillis) }
                    emit(
                        holds.associate { hold ->
                            val capPending = HoldPolicy.hasDailyCapHold(states.getValue(hold.blockId), nowMillis)
                            hold.blockId to hold.toStatus(capPending)
                        },
                    )
                    val earliestEndMillis = holds.minOfOrNull { it.untilMillis } ?: break
                    delay(maxOf(earliestEndMillis - nowMillis, MIN_WAIT_MILLIS))
                }
            }
            .distinctUntilChanged()

    private fun ActiveHold.toStatus(capPending: Boolean): BlockListStatus = when (reason) {
        BlockReason.COOLDOWN -> BlockListStatus.CoolingDown(untilMillis, dailyCapPending = capPending)
        BlockReason.DAILY_CAP -> BlockListStatus.DailyCapReached(untilMillis)
    }

    private companion object {
        /** Guards against a spin if the clock has not caught up with the wake-up. */
        const val MIN_WAIT_MILLIS = 1L
    }
}

package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.ports.input.ObserveEnforcedPackages
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.rules.HoldPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * Enabled-block packages plus the packages of every active hold ([HoldPolicy]), recomputed on each config or state
 * change. The states start as an empty map, so the enabled-block packages are reported even while storage is
 * unreadable at startup (holds join once the states arrive); a later outage keeps the last value, since the state
 * flow just stops emitting. A hold that expires leaves the set at the next change; until then an extra package is
 * harmless, since evaluating it answers NotEnforced.
 */
class ObserveEnforcedPackagesUseCase(
    private val blockRepository: BlockRepository,
    private val blockStateRepository: BlockStateRepository,
    private val clock: Clock,
) : ObserveEnforcedPackages {

    override fun invoke(): Flow<Set<String>> =
        combine(
            blockRepository.observeBlocks(),
            blockStateRepository.observeStates().onStart { emit(emptyMap()) },
        ) { blocks, states ->
            val nowMillis = clock.nowMillis()
            val enabledPackages = blocks.filter { it.enabled }.flatMap { it.packageNames }
            val heldPackages = states
                .filter { (blockId, state) -> HoldPolicy.holdOf(blockId, state, nowMillis) != null }
                .values
                .flatMap { it.holdPackageNames }
            (enabledPackages + heldPackages).toSet()
        }.distinctUntilChanged()
}

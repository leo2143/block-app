package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.ports.input.ObserveEnforcedPackages
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.rules.HoldPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Enabled-block packages plus the packages of every active hold ([HoldPolicy]), recomputed on each config or state
 * change. It emits only once both sources have emitted: while storage is unreadable the state flow stays silent and
 * so does this one (never an empty set that would drop the holds). A hold that expires leaves the set at the next
 * change; until then an extra package is harmless, since evaluating it answers NotEnforced.
 */
class ObserveEnforcedPackagesUseCase(
    private val blockRepository: BlockRepository,
    private val blockStateRepository: BlockStateRepository,
    private val clock: Clock,
) : ObserveEnforcedPackages {

    override fun invoke(): Flow<Set<String>> =
        combine(blockRepository.observeBlocks(), blockStateRepository.observeStates()) { blocks, states ->
            val nowMillis = clock.nowMillis()
            val enabledPackages = blocks.filter { it.enabled }.flatMap { it.packageNames }
            val heldPackages = states
                .filter { (blockId, state) -> HoldPolicy.holdOf(blockId, state, nowMillis) != null }
                .values
                .flatMap { it.holdPackageNames }
            (enabledPackages + heldPackages).toSet()
        }.distinctUntilChanged()
}

package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.BlockScreenState
import com.leo.blockapp.domain.ports.input.GetAppBlockingStatus
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.rules.BlockStatusResolver
import kotlinx.coroutines.flow.first

/** Reads config and states (display only, never a read-then-write) and resolves the status with [BlockStatusResolver]. */
class GetAppBlockingStatusUseCase(
    private val blockRepository: BlockRepository,
    private val blockStateRepository: BlockStateRepository,
    private val clock: Clock,
) : GetAppBlockingStatus {

    override suspend fun invoke(packageName: String): BlockScreenState {
        val blocks = blockRepository.getBlocks()
        val states = blockStateRepository.observeStates().first()
        return BlockStatusResolver.resolve(packageName, blocks, states, clock.nowMillis())
    }
}

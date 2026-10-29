package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.ports.input.ObserveBlocks
import com.leo.blockapp.domain.ports.output.BlockRepository
import kotlinx.coroutines.flow.Flow

class ObserveBlocksUseCase(private val blockRepository: BlockRepository) : ObserveBlocks {

    override fun invoke(): Flow<List<Block>> = blockRepository.observeBlocks()
}

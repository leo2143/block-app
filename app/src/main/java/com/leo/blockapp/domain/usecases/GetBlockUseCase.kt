package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.ports.input.GetBlock
import com.leo.blockapp.domain.ports.output.BlockRepository

class GetBlockUseCase(private val blockRepository: BlockRepository) : GetBlock {

    override suspend fun invoke(id: String): Block? = blockRepository.getBlocks().firstOrNull { it.id == id }
}

package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.DeletedBlock
import com.leo.blockapp.domain.ports.input.DeleteBlock
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockUpdate

/** Removes a block against the in-transaction snapshot and returns exactly what was removed, including its position. */
class DeleteBlockUseCase(private val blockRepository: BlockRepository) : DeleteBlock {

    override suspend fun invoke(id: String): DeletedBlock? = blockRepository.update { currentBlocks ->
        val index = currentBlocks.indexOfFirst { it.id == id }
        if (index < 0) {
            BlockUpdate(BlockChanges(), null)
        } else {
            BlockUpdate(BlockChanges(deletedIds = setOf(id)), DeletedBlock(currentBlocks[index]))
        }
    }
}

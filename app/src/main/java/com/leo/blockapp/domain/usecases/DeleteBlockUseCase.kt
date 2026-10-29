package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.ports.input.DeleteBlock
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockUpdate

/** Removes a block against the in-transaction snapshot and returns exactly what was removed. */
class DeleteBlockUseCase(private val blockRepository: BlockRepository) : DeleteBlock {

    override suspend fun invoke(id: String): Block? = blockRepository.update { currentBlocks ->
        val removed = currentBlocks.firstOrNull { it.id == id }
        val changes = if (removed == null) BlockChanges() else BlockChanges(deletedIds = setOf(id))
        BlockUpdate(changes, removed)
    }
}

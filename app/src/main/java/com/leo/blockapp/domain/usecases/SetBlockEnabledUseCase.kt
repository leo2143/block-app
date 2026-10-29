package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.ports.input.SetBlockEnabled
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockUpdate

/**
 * Flips only `enabled` against the in-transaction snapshot, so it cannot overwrite a concurrent change.
 * Setting the value the block already has writes nothing.
 */
class SetBlockEnabledUseCase(private val blockRepository: BlockRepository) : SetBlockEnabled {

    override suspend fun invoke(id: String, enabled: Boolean) {
        blockRepository.update { currentBlocks ->
            val block = currentBlocks.firstOrNull { it.id == id }
            val changes = if (block == null || block.enabled == enabled) {
                BlockChanges()
            } else {
                BlockChanges(upserts = listOf(block.copy(enabled = enabled)))
            }
            BlockUpdate(changes, Unit)
        }
    }
}

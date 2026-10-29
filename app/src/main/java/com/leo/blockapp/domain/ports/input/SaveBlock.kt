package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.SaveBlockResult

/** Input port: create or edit a block. Apps owned by other blocks must be confirmed to move. */
interface SaveBlock {
    suspend operator fun invoke(
        draft: BlockDraft,
        confirmedMovePackages: Set<String> = emptySet(),
    ): SaveBlockResult
}

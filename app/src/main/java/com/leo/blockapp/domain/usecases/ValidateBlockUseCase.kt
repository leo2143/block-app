package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.blocks.BlockValidator
import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.BlockViolation
import com.leo.blockapp.domain.ports.input.ValidateBlock

/** Exposes the domain validator to the UI without leaking `domain.blocks`. */
class ValidateBlockUseCase : ValidateBlock {

    override fun invoke(draft: BlockDraft): Set<BlockViolation> = BlockValidator.validate(draft)
}

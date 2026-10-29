package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.BlockDraft
import com.leo.blockapp.domain.model.BlockViolation

/** Input port: pure live validation for the editor. Same rules SaveBlock applies. */
interface ValidateBlock {
    operator fun invoke(draft: BlockDraft): Set<BlockViolation>
}

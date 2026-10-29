package com.leo.blockapp.domain.model

/**
 * A block removed by a delete. The block carries its stable [Block.position], which is all a restore needs to put
 * it back in its original place, in any undo order and even if other blocks were created or removed in between.
 */
data class DeletedBlock(
    val block: Block,
)

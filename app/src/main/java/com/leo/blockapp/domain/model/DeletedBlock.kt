package com.leo.blockapp.domain.model

/**
 * A block removed by a delete, with the position it had in the list at delete time, so an undo can put it back
 * where the user saw it. [index] is read inside the delete transaction, so it is exact for that write.
 */
data class DeletedBlock(
    val block: Block,
    val index: Int,
)

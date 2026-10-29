package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.DeletedBlock

/** Input port: delete a block and return it with its position so it can be restored. Null when the id is unknown. */
interface DeleteBlock {
    suspend operator fun invoke(id: String): DeletedBlock?
}

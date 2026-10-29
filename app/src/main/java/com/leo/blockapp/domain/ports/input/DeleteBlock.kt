package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.Block

/** Input port: delete a block and return it so it can be restored. Null when the id is unknown. */
interface DeleteBlock {
    suspend operator fun invoke(id: String): Block?
}

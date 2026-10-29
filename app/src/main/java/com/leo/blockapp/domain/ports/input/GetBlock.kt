package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.Block

/** Input port: one block by id, or null when it does not exist. */
interface GetBlock {
    suspend operator fun invoke(id: String): Block?
}

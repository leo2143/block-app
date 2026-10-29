package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.RestoreBlockResult

/** Input port: bring back a deleted block, keeping only the apps that are still free. */
interface RestoreBlock {
    suspend operator fun invoke(block: Block): RestoreBlockResult
}

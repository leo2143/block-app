package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.Block
import kotlinx.coroutines.flow.Flow

/** Input port: the stored blocks, emitting again on every change. */
interface ObserveBlocks {
    operator fun invoke(): Flow<List<Block>>
}

package com.leo.blockapp.domain.ports.input

import com.leo.blockapp.domain.model.BlockListStatus
import kotlinx.coroutines.flow.Flow

/**
 * Input port: the status line of every block that is held right now, keyed by block id (a block with no entry is
 * [BlockListStatus.Normal]). It re-emits when the stored states change and exactly when the earliest active hold
 * ends, with no polling. It stays silent while the states cannot be read: consumers must not treat a missing
 * emission as "nothing is held".
 */
fun interface ObserveBlockStatuses {
    operator fun invoke(): Flow<Map<String, BlockListStatus>>
}

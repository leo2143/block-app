package com.leo.blockapp.domain.ports.output

import com.leo.blockapp.domain.model.BlockState
import kotlinx.coroutines.flow.Flow

/**
 * Output port: enforcement state storage, keyed by block id. The only write path is [update]; there is no
 * `getState`, so reads go through `observeStates().first()` or happen inside [update].
 */
interface BlockStateRepository {
    /** Emits the full id-to-state map on every change. */
    fun observeStates(): Flow<Map<String, BlockState>>

    /**
     * Runs [transform] against the current states and applies its changes in ONE transaction, so a
     * read-validate-write never spans two calls and concurrent updates never lose each other. [transform] must not
     * suspend or do IO, and should be fast. An update with no upserts and no deletes performs NO write. An
     * IOException from the write propagates.
     */
    suspend fun <T> update(transform: (current: Map<String, BlockState>) -> BlockStateUpdate<T>): T
}

/**
 * Changes applied atomically by [BlockStateRepository.update]: [upserts] replace or add states by id, [deletedIds]
 * remove them, and deletion wins when an id is in both. Ids in [deletedIds] that are not stored are ignored.
 */
data class BlockStateUpdate<T>(
    val upserts: Map<String, BlockState> = emptyMap(),
    val deletedIds: Set<String> = emptySet(),
    val result: T,
)

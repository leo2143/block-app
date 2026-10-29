package com.leo.blockapp.domain.ports.output

import com.leo.blockapp.domain.model.Block
import kotlinx.coroutines.flow.Flow

/** Output port: block configuration storage. The only write path is [update]. */
interface BlockRepository {
    /** Emits the full block list on every change. */
    fun observeBlocks(): Flow<List<Block>>

    /** One-off read for display only. Never use it for read-then-write; use [update] instead. */
    suspend fun getBlocks(): List<Block>

    /**
     * Runs [transform] against the current blocks and applies its changes in ONE transaction, so a
     * read-validate-write never spans two calls. [transform] must be pure and fast (no suspend, no IO).
     * An empty [BlockChanges] performs no write. An IOException from the write propagates.
     */
    suspend fun <T> update(transform: (current: List<Block>) -> BlockUpdate<T>): T
}

data class BlockUpdate<T>(
    val changes: BlockChanges,
    val result: T,
)

/**
 * Changes applied atomically by [BlockRepository.update]. Contract every implementation must honor:
 * - [upserts] must not contain duplicate ids (implementations reject them with an IllegalArgumentException).
 * - An upsert replaces the stored block with the same id in place, keeping its position.
 * - An upsert of an id that is not stored appends the block at the end, in the order given.
 * - Deletion wins: an id present in both [upserts] and [deletedIds] ends up deleted, never stored.
 * - Ids in [deletedIds] that are not stored are ignored.
 */
data class BlockChanges(
    val upserts: List<Block> = emptyList(),
    val deletedIds: Set<String> = emptySet(),
)

package com.leo.blockapp.domain.ports.output

import com.leo.blockapp.domain.model.Block
import kotlinx.coroutines.flow.Flow

/** Output port: block configuration storage. The only write path is [update]. */
interface BlockRepository {
    fun observeBlocks(): Flow<List<Block>>

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

data class BlockChanges(
    val upserts: List<Block> = emptyList(),
    val deletedIds: Set<String> = emptySet(),
)

package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory repository. [update] is serialized with a [Mutex] so it behaves like a DataStore edit,
 * and every non-empty write is recorded in [writes].
 */
class FakeBlockRepository(initialBlocks: List<Block> = emptyList()) : BlockRepository {

    private val blocksState = MutableStateFlow(initialBlocks)
    private val updateMutex = Mutex()
    private val recordedWrites = mutableListOf<BlockChanges>()

    val writes: List<BlockChanges> get() = recordedWrites.toList()

    override fun observeBlocks(): Flow<List<Block>> = blocksState

    override suspend fun getBlocks(): List<Block> = blocksState.value

    override suspend fun <T> update(transform: (current: List<Block>) -> BlockUpdate<T>): T =
        updateMutex.withLock {
            val blockUpdate = transform(blocksState.value)
            val changes = blockUpdate.changes
            if (changes.upserts.isNotEmpty() || changes.deletedIds.isNotEmpty()) {
                blocksState.value = apply(blocksState.value, changes)
                recordedWrites += changes
            }
            blockUpdate.result
        }

    private fun apply(current: List<Block>, changes: BlockChanges): List<Block> {
        val upsertsById = changes.upserts.associateBy { it.id }
        val kept = current
            .filterNot { it.id in changes.deletedIds }
            .map { upsertsById[it.id] ?: it }
        val currentIds = current.map { it.id }.toSet()
        val appended = changes.upserts.filter { it.id !in currentIds && it.id !in changes.deletedIds }
        return kept + appended
    }
}

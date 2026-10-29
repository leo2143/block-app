package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * In-memory repository. [update] is serialized with a [Mutex] so it behaves like a DataStore edit,
 * and every non-empty write is recorded in [writes]. Enforces the [BlockChanges] contract, including
 * insertion positions ([BlockChanges.insertAt]), and tracks [maxConcurrentUpdates].
 *
 * [beforeApply] runs after the transform and before the changes are applied, i.e. inside the
 * read-then-write window. Tests use it to suspend (e.g. `yield()`) so concurrent updates really
 * interleave unless the lock serializes them.
 *
 * Set [writeFailure] to make writes fail with an IOException; updates that change nothing still succeed.
 */
class FakeBlockRepository(
    initialBlocks: List<Block> = emptyList(),
    private val beforeApply: suspend () -> Unit = {},
) : BlockRepository {

    private val blocksState = MutableStateFlow(initialBlocks)
    private val updateMutex = Mutex()
    private val recordedWrites = mutableListOf<BlockChanges>()

    val writes: List<BlockChanges> get() = recordedWrites.toList()

    private var activeUpdates = 0

    /** Highest number of [update] calls running at once (transform through apply). Serialized updates keep it at 1. */
    var maxConcurrentUpdates = 0
        private set

    /** When set, every update that would write throws it instead (like a failing DataStore edit). */
    @Volatile
    var writeFailure: IOException? = null

    override fun observeBlocks(): Flow<List<Block>> = blocksState

    override suspend fun getBlocks(): List<Block> = blocksState.value

    override suspend fun <T> update(transform: (current: List<Block>) -> BlockUpdate<T>): T =
        updateMutex.withLock {
            activeUpdates += 1
            maxConcurrentUpdates = maxOf(maxConcurrentUpdates, activeUpdates)
            try {
                val snapshot = blocksState.value
                val blockUpdate = transform(snapshot)
                val changes = blockUpdate.changes
                val upsertIds = changes.upserts.map { it.id }
                require(upsertIds.size == upsertIds.toSet().size) { "Duplicate ids in upserts: $upsertIds" }
                beforeApply()
                if (changes.upserts.isNotEmpty() || changes.deletedIds.isNotEmpty()) {
                    writeFailure?.let { throw it }
                    blocksState.value = apply(snapshot, changes)
                    recordedWrites += changes
                }
                blockUpdate.result
            } finally {
                activeUpdates -= 1
            }
        }

    private fun apply(current: List<Block>, changes: BlockChanges): List<Block> {
        val upsertsById = changes.upserts.associateBy { it.id }
        val kept = current
            .filterNot { it.id in changes.deletedIds }
            .map { upsertsById[it.id] ?: it }
        val currentIds = current.map { it.id }.toSet()
        val added = changes.upserts.filter { it.id !in currentIds && it.id !in changes.deletedIds }
        val result = kept.toMutableList()
        added.forEach { block ->
            val index = changes.insertAt[block.id]
            if (index == null) result += block else result.add(index.coerceIn(0, result.size), block)
        }
        return result
    }
}

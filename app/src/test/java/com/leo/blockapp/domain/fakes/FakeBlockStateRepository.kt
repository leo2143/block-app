package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.BlockStateUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * In-memory state repository. [update] is serialized with a [Mutex] so it behaves like a DataStore edit, and every
 * non-empty write is recorded in [writes]. Tracks [maxConcurrentUpdates].
 *
 * [beforeApply] runs after the transform and before the changes are applied, i.e. inside the read-then-write
 * window. Tests use it to suspend (e.g. `yield()`) so concurrent updates really interleave unless the lock
 * serializes them.
 *
 * Set [writeFailure] to make writes fail with an IOException; updates that change nothing still succeed.
 */
class FakeBlockStateRepository(
    initialStates: Map<String, BlockState> = emptyMap(),
    private val beforeApply: suspend () -> Unit = {},
) : BlockStateRepository {

    private val statesFlow = MutableStateFlow(initialStates)
    private val updateMutex = Mutex()
    private val recordedWrites = mutableListOf<BlockStateUpdate<*>>()

    val writes: List<BlockStateUpdate<*>> get() = recordedWrites.toList()

    private var activeUpdates = 0

    /** Highest number of [update] calls running at once (transform through apply). Serialized updates keep it at 1. */
    var maxConcurrentUpdates = 0
        private set

    /** When set, every update that would write throws it instead (like a failing DataStore edit). */
    @Volatile
    var writeFailure: IOException? = null

    override fun observeStates(): Flow<Map<String, BlockState>> = statesFlow

    override suspend fun <T> update(transform: (current: Map<String, BlockState>) -> BlockStateUpdate<T>): T =
        updateMutex.withLock {
            activeUpdates += 1
            maxConcurrentUpdates = maxOf(maxConcurrentUpdates, activeUpdates)
            try {
                val snapshot = statesFlow.value
                val stateUpdate = transform(snapshot)
                beforeApply()
                if (stateUpdate.upserts.isNotEmpty() || stateUpdate.deletedIds.isNotEmpty()) {
                    writeFailure?.let { throw it }
                    statesFlow.value = (snapshot + stateUpdate.upserts) - stateUpdate.deletedIds
                    recordedWrites += stateUpdate
                }
                stateUpdate.result
            } finally {
                activeUpdates -= 1
            }
        }
}

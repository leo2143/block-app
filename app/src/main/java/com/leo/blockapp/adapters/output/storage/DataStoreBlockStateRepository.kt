package com.leo.blockapp.adapters.output.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.BlockStateUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException

/**
 * [BlockStateRepository] backed by its own Preferences DataStore (`block_state`) holding one JSON blob under
 * [STATES_KEY].
 *
 * [update] reads, transforms, applies the changes and encodes inside ONE `dataStore.edit`, so DataStore's own
 * serialization of edits is what makes every read-modify-write atomic. A read IOException while observing is retried
 * with a bounded backoff WITHOUT emitting, so observers keep their last value (the blocker must not fail open during
 * an outage); any other failure is rethrown. A read or write IOException inside [update] propagates. An update
 * without upserts and deletes writes nothing.
 *
 * When the stored blob is non-blank but unreadable (bad JSON or unsupported schema version), [update] first copies the
 * raw blob to [UNREADABLE_BACKUP_KEY] in the same edit (the first backup is kept, never overwritten) and then
 * proceeds from an empty base. A corrupt file is replaced at the file level by the DataStore corruption handler.
 */
class DataStoreBlockStateRepository(
    private val dataStore: DataStore<Preferences>,
) : BlockStateRepository {

    override fun observeStates(): Flow<Map<String, BlockState>> =
        dataStore.data
            .retryWhen { failure, attempt ->
                if (failure !is IOException) return@retryWhen false
                // Nothing is emitted: observers keep their last value (an empty map would drop every held package
                // during an outage), and the stream stays alive to pick the store up once it recovers.
                delay((RETRY_BASE_DELAY_MS shl attempt.toInt().coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(RETRY_MAX_DELAY_MS))
                true
            }
            .map { preferences -> BlockStateJsonMapper.decode(preferences[STATES_KEY]) }
            .distinctUntilChanged()

    override suspend fun <T> update(transform: (current: Map<String, BlockState>) -> BlockStateUpdate<T>): T {
        var outcome: BlockStateUpdate<T>? = null
        dataStore.edit { preferences ->
            val storedJson = preferences[STATES_KEY]
            if (BlockStateJsonMapper.isUnreadable(storedJson) && UNREADABLE_BACKUP_KEY !in preferences) {
                // Keep the first unreadable blob so the write below cannot silently erase the original holds.
                preferences[UNREADABLE_BACKUP_KEY] = checkNotNull(storedJson)
            }
            val current = BlockStateJsonMapper.decode(storedJson)
            val stateUpdate = transform(current)
            if (stateUpdate.upserts.isNotEmpty() || stateUpdate.deletedIds.isNotEmpty()) {
                val updated = (current + stateUpdate.upserts) - stateUpdate.deletedIds
                preferences[STATES_KEY] = BlockStateJsonMapper.encode(updated)
            }
            outcome = stateUpdate
        }
        return checkNotNull(outcome) { "DataStore edit finished without running the transform" }.result
    }

    internal companion object {
        val STATES_KEY = stringPreferencesKey("block_states_json")
        val UNREADABLE_BACKUP_KEY = stringPreferencesKey("block_states_json_unreadable")
        private const val RETRY_BASE_DELAY_MS = 500L
        private const val RETRY_MAX_DELAY_MS = 30_000L
        private const val MAX_BACKOFF_SHIFT = 10
    }
}

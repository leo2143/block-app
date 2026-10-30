package com.leo.blockapp.adapters.output.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.BlockStateUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException

/**
 * [BlockStateRepository] backed by its own Preferences DataStore (`block_state`) holding one JSON blob under
 * [STATES_KEY].
 *
 * [update] reads, transforms, applies the changes and encodes inside ONE `dataStore.edit`, so DataStore's own
 * serialization of edits is what makes every read-modify-write atomic. Reads swallow only IOException (accepted
 * risk: an unreadable store shows no states, and observation retries with a bounded backoff so it recovers); any
 * other failure is rethrown. A write IOException propagates. An update without upserts and deletes writes nothing.
 */
class DataStoreBlockStateRepository(
    private val dataStore: DataStore<Preferences>,
) : BlockStateRepository {

    override fun observeStates(): Flow<Map<String, BlockState>> =
        dataStore.data
            .retryWhen { failure, attempt ->
                if (failure !is IOException) return@retryWhen false
                // Observers see no states, and the stream stays alive to pick the store up once it recovers.
                emit(emptyPreferences())
                delay((RETRY_BASE_DELAY_MS shl attempt.toInt().coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(RETRY_MAX_DELAY_MS))
                true
            }
            .map { preferences -> BlockStateJsonMapper.decode(preferences[STATES_KEY]) }

    override suspend fun <T> update(transform: (current: Map<String, BlockState>) -> BlockStateUpdate<T>): T {
        var outcome: BlockStateUpdate<T>? = null
        dataStore.edit { preferences ->
            val current = BlockStateJsonMapper.decode(preferences[STATES_KEY])
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
        private const val RETRY_BASE_DELAY_MS = 500L
        private const val RETRY_MAX_DELAY_MS = 30_000L
        private const val MAX_BACKOFF_SHIFT = 10
    }
}

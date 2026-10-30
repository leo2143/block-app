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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.io.IOException

/**
 * [BlockStateRepository] backed by its own Preferences DataStore (`block_state`) holding one JSON blob under
 * [STATES_KEY].
 *
 * [update] reads, transforms, applies the changes and encodes inside ONE `dataStore.edit`, so DataStore's own
 * serialization of edits is what makes every read-modify-write atomic. A read IOException while observing is retried
 * with a bounded backoff (500 ms up to 30 s, restarted after every successful emission) WITHOUT emitting, so observers
 * keep their last value (the blocker must not fail open during an outage) and, while storage stays unreadable from the
 * start, [observeStates] never emits; any other failure is rethrown. A read or write IOException inside [update]
 * propagates. An update without upserts and deletes writes nothing.
 *
 * When the stored blob is lossy (non-blank and unreadable as a whole, an unsupported schema version, or with at least
 * one malformed entry), [update] first copies the raw blob to [UNREADABLE_BACKUP_KEY] in the same edit (the first
 * backup is kept, never overwritten) and then proceeds from whatever decoded. A blob unreadable as a whole observes as
 * an empty map until the next update. A corrupt file is replaced at the file level by the DataStore corruption handler.
 */
class DataStoreBlockStateRepository(
    private val dataStore: DataStore<Preferences>,
) : BlockStateRepository {

    override fun observeStates(): Flow<Map<String, BlockState>> =
        flow {
            var attempt = 0
            while (true) {
                var readFailed = false
                // A successful emission resets the backoff, so a later outage retries after the base delay again.
                // Only upstream failures are caught here, so a downstream exception is never swallowed.
                emitAll(
                    dataStore.data
                        .onEach { attempt = 0 }
                        .catch { failure ->
                            if (failure !is IOException) throw failure
                            readFailed = true
                        },
                )
                if (!readFailed) return@flow
                // Nothing is emitted: observers keep their last value (an empty map would drop every held package
                // during an outage), and the stream stays alive to pick the store up once it recovers.
                delay((RETRY_BASE_DELAY_MS shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(RETRY_MAX_DELAY_MS))
                attempt++
            }
        }
            .map { preferences -> BlockStateJsonMapper.decode(preferences[STATES_KEY]).states }
            .distinctUntilChanged()

    override suspend fun <T> update(transform: (current: Map<String, BlockState>) -> BlockStateUpdate<T>): T {
        var outcome: BlockStateUpdate<T>? = null
        dataStore.edit { preferences ->
            val storedJson = preferences[STATES_KEY]
            val decoded = BlockStateJsonMapper.decode(storedJson)
            if (decoded.isLossy && UNREADABLE_BACKUP_KEY !in preferences) {
                // Keep the first lossy blob so the write below cannot silently erase the original holds.
                preferences[UNREADABLE_BACKUP_KEY] = checkNotNull(storedJson)
            }
            val current = decoded.states
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

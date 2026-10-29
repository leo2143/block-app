package com.leo.blockapp.adapters.output.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException
import javax.inject.Inject

/**
 * [BlockRepository] backed by a Preferences DataStore holding one JSON blob under [BLOCKS_KEY].
 *
 * [update] reads, transforms, applies the changes and encodes inside ONE `dataStore.edit`, so DataStore's own
 * serialization of edits is what makes every read-validate-write atomic. Reads swallow only IOException (accepted
 * risk: an unreadable store shows an empty list, and observation retries with a bounded backoff so it recovers);
 * any other failure is rethrown. A write IOException propagates.
 */
class DataStoreBlockRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : BlockRepository {

    override fun observeBlocks(): Flow<List<Block>> =
        dataStore.data
            .retryWhen { failure, attempt ->
                if (failure !is IOException) return@retryWhen false
                // Observers see an empty list, and the stream stays alive to pick the store up once it recovers.
                emit(emptyPreferences())
                delay((RETRY_BASE_DELAY_MS shl attempt.toInt().coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(RETRY_MAX_DELAY_MS))
                true
            }
            .map { preferences -> BlockJsonMapper.decode(preferences[BLOCKS_KEY]) }

    override suspend fun getBlocks(): List<Block> = observeBlocks().first()

    override suspend fun <T> update(transform: (current: List<Block>) -> BlockUpdate<T>): T {
        var outcome: BlockUpdate<T>? = null
        dataStore.edit { preferences ->
            val current = BlockJsonMapper.decode(preferences[BLOCKS_KEY])
            val blockUpdate = transform(current)
            val changes = blockUpdate.changes
            val upsertIds = changes.upserts.map { it.id }
            require(upsertIds.size == upsertIds.toSet().size) { "Duplicate ids in upserts: $upsertIds" }
            if (changes.upserts.isNotEmpty() || changes.deletedIds.isNotEmpty()) {
                val updated = applyChanges(current, changes)
                requireOrderedByPositionAndId(updated)
                preferences[BLOCKS_KEY] = BlockJsonMapper.encode(updated)
            }
            outcome = blockUpdate
        }
        return checkNotNull(outcome) { "DataStore edit finished without running the transform" }.result
    }

    private fun applyChanges(current: List<Block>, changes: BlockChanges): List<Block> {
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

    private fun requireOrderedByPositionAndId(blocks: List<Block>) {
        val ordered = blocks.sortedWith(compareBy<Block> { it.position }.thenBy { it.id })
        require(ordered == blocks) {
            "Blocks must be ordered by (position, id): ${blocks.map { it.position to it.id }}"
        }
    }

    internal companion object {
        val BLOCKS_KEY = stringPreferencesKey("blocks_json")
        private const val RETRY_BASE_DELAY_MS = 500L
        private const val RETRY_MAX_DELAY_MS = 30_000L
        private const val MAX_BACKOFF_SHIFT = 10
    }
}

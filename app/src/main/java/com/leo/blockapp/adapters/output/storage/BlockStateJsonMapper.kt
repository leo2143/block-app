package com.leo.blockapp.adapters.output.storage

import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockState
import kotlinx.serialization.json.Json

/**
 * Converts the block states to and from the JSON blob kept in the `block_state` DataStore.
 *
 * Accepted risk (P3): null/blank/undecodable JSON or an unsupported schema version decodes to an empty map, and the
 * next write overwrites the stored blob, so corruption drops active holds. An unknown hold reason is NOT corruption:
 * it decodes to null and the hold fields are kept (fail-closed, the hold policy derives the cap from a live until).
 */
internal object BlockStateJsonMapper {

    const val SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(states: Map<String, BlockState>): String {
        val dto = StoredBlockStatesDto(
            schemaVersion = SCHEMA_VERSION,
            states = states.mapValues { (_, state) -> state.toDto() },
        )
        return json.encodeToString(StoredBlockStatesDto.serializer(), dto)
    }

    fun decode(stored: String?): Map<String, BlockState> {
        if (stored.isNullOrBlank()) return emptyMap()
        val dto = try {
            json.decodeFromString(StoredBlockStatesDto.serializer(), stored)
        } catch (_: IllegalArgumentException) {
            // Also covers SerializationException, which extends IllegalArgumentException.
            return emptyMap()
        }
        if (dto.schemaVersion != SCHEMA_VERSION) return emptyMap()
        return dto.states.mapValues { (_, state) -> state.toDomain() }
    }

    private fun BlockState.toDto() = BlockStateDto(
        cycleStartMillis = cycleStartMillis,
        cooldownEndMillis = cooldownEndMillis,
        cyclesToday = cyclesToday,
        cyclesDayStartMillis = cyclesDayStartMillis,
        holdUntilMillis = holdUntilMillis,
        holdReason = holdReason?.name,
        holdPackageNames = holdPackageNames.toList(),
        blockName = blockName,
    )

    private fun BlockStateDto.toDomain() = BlockState(
        cycleStartMillis = cycleStartMillis,
        cooldownEndMillis = cooldownEndMillis,
        cyclesToday = cyclesToday,
        cyclesDayStartMillis = cyclesDayStartMillis,
        holdUntilMillis = holdUntilMillis,
        holdReason = BlockReason.entries.firstOrNull { it.name == holdReason },
        holdPackageNames = LinkedHashSet(holdPackageNames),
        blockName = blockName,
    )
}

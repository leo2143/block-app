package com.leo.blockapp.adapters.output.storage

import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Converts the block states to and from the JSON blob kept in the `block_state` DataStore.
 *
 * Entries decode independently: a malformed entry drops only itself and the others survive. Null/blank blobs decode to
 * an empty map; a non-blank blob that is undecodable as a whole or has an unsupported schema version also decodes to
 * an empty map but is reported by [isUnreadable], so the repository can back the raw blob up before a write replaces
 * it (the blocker must not silently lose active holds). An unknown hold reason is NOT corruption: it decodes to null
 * and the hold fields are kept (fail-closed, the hold policy derives the cap from a live until).
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

    /** True when [stored] is a non-blank blob that cannot be read as a whole (bad JSON, bad shape or unsupported schema). */
    fun isUnreadable(stored: String?): Boolean = !stored.isNullOrBlank() && readStatesObject(stored) == null

    fun decode(stored: String?): Map<String, BlockState> {
        if (stored.isNullOrBlank()) return emptyMap()
        val statesObject = readStatesObject(stored) ?: return emptyMap()
        return statesObject.mapNotNull { (id, element) ->
            val dto = try {
                json.decodeFromJsonElement(BlockStateDto.serializer(), element)
            } catch (_: IllegalArgumentException) {
                // A malformed entry is dropped alone; SerializationException extends IllegalArgumentException.
                return@mapNotNull null
            }
            id to dto.toDomain()
        }.toMap()
    }

    /** The `states` object of a readable blob with the supported schema version, or null when the blob is unreadable. */
    private fun readStatesObject(stored: String): JsonObject? {
        val root = try {
            json.parseToJsonElement(stored) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (version != SCHEMA_VERSION) return null
        return root["states"] as? JsonObject
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

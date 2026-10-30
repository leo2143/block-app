package com.leo.blockapp.adapters.output.storage

import kotlinx.serialization.Serializable

/** Persisted shape of the block states, keyed by block id. Private to the storage adapter. */
@Serializable
internal data class StoredBlockStatesDto(
    val schemaVersion: Int,
    val states: Map<String, BlockStateDto>,
)

@Serializable
internal data class BlockStateDto(
    val cycleStartMillis: Long,
    val cooldownEndMillis: Long? = null,
    val cyclesToday: Int,
    val cyclesDayStartMillis: Long,
    val holdUntilMillis: Long? = null,
    /**
     * Stored as text so a reason this build does not know decodes to null instead of dropping the whole hold:
     * the domain derives DAILY_CAP from a live [holdUntilMillis] with no reason.
     */
    val holdReason: String? = null,
    /** Written in insertion order; decoded into an insertion-ordered set. */
    val holdPackageNames: List<String> = emptyList(),
    val blockName: String,
)

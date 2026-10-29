package com.leo.blockapp.adapters.output.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Persisted shape of the block list. Private to the storage adapter: the domain never sees serialization. */
@Serializable
internal data class StoredBlocksDto(
    val schemaVersion: Int,
    val blocks: List<BlockDto>,
)

@Serializable
internal data class BlockDto(
    val id: String,
    val name: String,
    /** Written in selection order; decoded into an insertion-ordered set. */
    val packageNames: List<String>,
    val rule: BlockRuleDto,
    val position: Long,
    val enabled: Boolean,
)

@Serializable
internal sealed interface BlockRuleDto {

    @Serializable
    @SerialName("accumulated_usage_with_cooldown")
    data class AccumulatedUsageWithCooldownDto(
        val usageLimitMinutes: Int,
        val cooldownMinutes: Int,
        val dailyCycleCap: Int? = null,
        val inactivityResetMinutes: Int? = null,
    ) : BlockRuleDto
}

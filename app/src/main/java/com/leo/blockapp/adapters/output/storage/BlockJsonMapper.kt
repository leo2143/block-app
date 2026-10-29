package com.leo.blockapp.adapters.output.storage

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Converts the block list to and from the JSON blob kept in DataStore. Blocks are always sorted by
 * (position, id) so the stored list honors the [com.leo.blockapp.domain.ports.output.BlockRepository] order.
 *
 * Accepted risk (phase 2): null/blank/undecodable JSON or a schema version newer than [SCHEMA_VERSION] decodes to an
 * empty list, and the next write overwrites the stored blob. Revisit with backup/migration before any schema v2.
 */
internal object BlockJsonMapper {

    const val SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    fun encode(blocks: List<Block>): String {
        val dto = StoredBlocksDto(
            schemaVersion = SCHEMA_VERSION,
            blocks = blocks.sortedWith(POSITION_THEN_ID).map { it.toDto() },
        )
        return json.encodeToString(StoredBlocksDto.serializer(), dto)
    }

    fun decode(stored: String?): List<Block> {
        if (stored.isNullOrBlank()) return emptyList()
        val dto = try {
            json.decodeFromString(StoredBlocksDto.serializer(), stored)
        } catch (_: SerializationException) {
            return emptyList()
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
        if (dto.schemaVersion > SCHEMA_VERSION) return emptyList()
        return dto.blocks.map { it.toDomain() }.sortedWith(POSITION_THEN_ID)
    }

    private val POSITION_THEN_ID = compareBy<Block> { it.position }.thenBy { it.id }

    private fun Block.toDto() = BlockDto(
        id = id,
        name = name,
        packageNames = packageNames.toList(),
        rule = rule.toDto(),
        position = position,
        enabled = enabled,
    )

    private fun BlockRule.toDto(): BlockRuleDto = when (this) {
        is BlockRule.AccumulatedUsageWithCooldown -> BlockRuleDto.AccumulatedUsageWithCooldownDto(
            usageLimitMinutes = usageLimitMinutes,
            cooldownMinutes = cooldownMinutes,
            dailyCycleCap = dailyCycleCap,
            inactivityResetMinutes = inactivityResetMinutes,
        )
    }

    private fun BlockDto.toDomain() = Block(
        id = id,
        name = name,
        packageNames = LinkedHashSet(packageNames),
        rule = rule.toDomain(),
        position = position,
        enabled = enabled,
    )

    private fun BlockRuleDto.toDomain(): BlockRule = when (this) {
        is BlockRuleDto.AccumulatedUsageWithCooldownDto -> BlockRule.AccumulatedUsageWithCooldown(
            usageLimitMinutes = usageLimitMinutes,
            cooldownMinutes = cooldownMinutes,
            dailyCycleCap = dailyCycleCap,
            inactivityResetMinutes = inactivityResetMinutes,
        )
    }
}

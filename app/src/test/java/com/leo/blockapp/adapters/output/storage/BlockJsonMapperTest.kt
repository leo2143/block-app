package com.leo.blockapp.adapters.output.storage

import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.model.BlockRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockJsonMapperTest {

    private val fullRule = BlockRule.AccumulatedUsageWithCooldown(
        usageLimitMinutes = 10,
        cooldownMinutes = 60,
        dailyCycleCap = 3,
        inactivityResetMinutes = 45,
    )

    private val minimalRule = BlockRule.AccumulatedUsageWithCooldown(usageLimitMinutes = 5, cooldownMinutes = 15)

    private fun block(
        id: String,
        position: Long,
        packageNames: Set<String> = linkedSetOf("com.instagram", "com.twitter"),
        rule: BlockRule = fullRule,
        enabled: Boolean = true,
    ) = Block(id, "Block $id", packageNames, rule, position, enabled)

    @Test
    fun `round trip keeps every field including optional rule values`() {
        val blocks = listOf(
            block("B1", position = 0, rule = fullRule),
            block("B2", position = 1, rule = minimalRule, enabled = false),
        )

        val decoded = BlockJsonMapper.decode(BlockJsonMapper.encode(blocks))

        assertEquals(blocks, decoded)
        assertEquals(null, (decoded[1].rule as BlockRule.AccumulatedUsageWithCooldown).dailyCycleCap)
        assertEquals(null, (decoded[1].rule as BlockRule.AccumulatedUsageWithCooldown).inactivityResetMinutes)
        assertEquals(false, decoded[1].enabled)
    }

    @Test
    fun `round trip keeps joinedAt per package`() {
        val withJoinedAt = block("B1", position = 0).copy(
            joinedAtMillisByPackage = mapOf("com.instagram" to 1_000L, "com.twitter" to 2_000L),
        )

        val decoded = BlockJsonMapper.decode(BlockJsonMapper.encode(listOf(withJoinedAt)))

        assertEquals(listOf(withJoinedAt), decoded)
    }

    @Test
    fun `a legacy blob without joinedAt decodes to an empty map`() {
        val legacy = """
            {"schemaVersion":1,"blocks":[{"id":"B1","name":"Old","packageNames":["a"],
            "rule":{"type":"accumulated_usage_with_cooldown","usageLimitMinutes":10,"cooldownMinutes":60},
            "position":0,"enabled":true}]}
        """.trimIndent()

        val decoded = BlockJsonMapper.decode(legacy)

        assertEquals(emptyMap<String, Long>(), decoded.single().joinedAtMillisByPackage)
    }

    @Test
    fun `round trip preserves the selection order of package names`() {
        val ordered = linkedSetOf("z.last.alphabetically", "a.first.alphabetically", "m.middle")

        val decoded = BlockJsonMapper.decode(BlockJsonMapper.encode(listOf(block("B1", 0, packageNames = ordered))))

        assertEquals(listOf("z.last.alphabetically", "a.first.alphabetically", "m.middle"), decoded.single().packageNames.toList())
    }

    @Test
    fun `round trip preserves position`() {
        val decoded = BlockJsonMapper.decode(BlockJsonMapper.encode(listOf(block("B1", position = 9_000_000_000L))))

        assertEquals(9_000_000_000L, decoded.single().position)
    }

    @Test
    fun `decode sorts blocks by position then id`() {
        val unsorted = listOf(block("B3", 2), block("B2", 1), block("B1", 1))

        val decoded = BlockJsonMapper.decode(BlockJsonMapper.encode(unsorted))

        assertEquals(listOf("B1", "B2", "B3"), decoded.map { it.id })
    }

    @Test
    fun `encode writes schema version 1, the rule type discriminator and ordered packages`() {
        val json = BlockJsonMapper.encode(listOf(block("B1", 0, packageNames = linkedSetOf("b", "a"))))

        assertTrue(json, json.contains("\"schemaVersion\":1"))
        assertTrue(json, json.contains("\"type\":\"accumulated_usage_with_cooldown\""))
        assertTrue(json, json.contains("\"packageNames\":[\"b\",\"a\"]"))
    }

    @Test
    fun `an empty list round trips to an empty list`() {
        assertEquals(emptyList<Block>(), BlockJsonMapper.decode(BlockJsonMapper.encode(emptyList())))
    }

    @Test
    fun `null, blank and garbage decode to an empty list`() {
        assertEquals(emptyList<Block>(), BlockJsonMapper.decode(null))
        assertEquals(emptyList<Block>(), BlockJsonMapper.decode("   "))
        assertEquals(emptyList<Block>(), BlockJsonMapper.decode("not json at all"))
        assertEquals(emptyList<Block>(), BlockJsonMapper.decode("{\"schemaVersion\":1,\"blocks\":\"oops\"}"))
    }

    @Test
    fun `a future schema version decodes to an empty list`() {
        val future = BlockJsonMapper.encode(listOf(block("B1", 0))).replace("\"schemaVersion\":1", "\"schemaVersion\":2")

        assertEquals(emptyList<Block>(), BlockJsonMapper.decode(future))
    }

    @Test
    fun `unknown keys are ignored`() {
        val json = """
            {"schemaVersion":1,"extra":true,"blocks":[{"id":"B1","name":"N","packageNames":["a"],"position":4,"enabled":true,"unknown":1,
            "rule":{"type":"accumulated_usage_with_cooldown","usageLimitMinutes":10,"cooldownMinutes":60,"future":"x"}}]}
        """.trimIndent()

        val decoded = BlockJsonMapper.decode(json)

        assertEquals(listOf(Block("B1", "N", linkedSetOf("a"), minimalRule.copy(usageLimitMinutes = 10, cooldownMinutes = 60), 4)), decoded)
    }
}

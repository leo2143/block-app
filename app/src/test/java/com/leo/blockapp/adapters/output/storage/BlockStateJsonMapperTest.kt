package com.leo.blockapp.adapters.output.storage

import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockStateJsonMapperTest {

    private val full = BlockState(
        cycleStartMillis = 1_000L,
        cooldownEndMillis = 9_000L,
        cyclesToday = 2,
        cyclesDayStartMillis = 500L,
        holdUntilMillis = 9_500L,
        holdReason = BlockReason.DAILY_CAP,
        holdPackageNames = linkedSetOf("z", "a"),
        blockName = "Social",
    )

    private fun decodeStates(stored: String?) = BlockStateJsonMapper.decode(stored).states

    private val minimal = BlockState.initial(blockName = "Idle", startOfTodayMillis = 500L)

    @Test
    fun `round trip keeps every field including nulls and hold packages`() {
        val states = mapOf("B1" to full, "B2" to minimal)

        val decoded = decodeStates(BlockStateJsonMapper.encode(states))

        assertEquals(states, decoded)
        assertEquals(null, decoded.getValue("B2").cooldownEndMillis)
        assertEquals(null, decoded.getValue("B2").holdUntilMillis)
        assertEquals(null, decoded.getValue("B2").holdReason)
        assertEquals(listOf("z", "a"), decoded.getValue("B1").holdPackageNames.toList())
    }

    @Test
    fun `round trip keeps both hold reasons`() {
        val states = mapOf(
            "B1" to full.copy(holdReason = BlockReason.COOLDOWN),
            "B2" to full.copy(holdReason = BlockReason.DAILY_CAP),
        )

        assertEquals(states, decodeStates(BlockStateJsonMapper.encode(states)))
    }

    @Test
    fun `an empty map round trips`() {
        assertEquals(emptyMap<String, BlockState>(), decodeStates(BlockStateJsonMapper.encode(emptyMap())))
    }

    @Test
    fun `the encoded blob carries schema version 1`() {
        assertTrue(BlockStateJsonMapper.encode(mapOf("B1" to full)).contains("\"schemaVersion\":1"))
    }

    @Test
    fun `null and blank blobs decode to an empty map`() {
        assertEquals(emptyMap<String, BlockState>(), decodeStates(null))
        assertEquals(emptyMap<String, BlockState>(), decodeStates(""))
        assertEquals(emptyMap<String, BlockState>(), decodeStates("   "))
    }

    @Test
    fun `undecodable json decodes to an empty map`() {
        assertEquals(emptyMap<String, BlockState>(), decodeStates("{not json"))
        assertEquals(emptyMap<String, BlockState>(), decodeStates("[]"))
        assertEquals(emptyMap<String, BlockState>(), decodeStates("""{"schemaVersion":1}"""))
    }

    @Test
    fun `an unknown newer schema version decodes to an empty map`() {
        val newer = BlockStateJsonMapper.encode(mapOf("B1" to full)).replace("\"schemaVersion\":1", "\"schemaVersion\":2")

        assertEquals(emptyMap<String, BlockState>(), decodeStates(newer))
    }

    @Test
    fun `a zero or negative schema version decodes to an empty map`() {
        val encoded = BlockStateJsonMapper.encode(mapOf("B1" to full))

        assertEquals(emptyMap<String, BlockState>(), decodeStates(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":0")))
        assertEquals(emptyMap<String, BlockState>(), decodeStates(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":-1")))
    }

    @Test
    fun `a malformed entry drops only that entry and keeps the others`() {
        val blob = """
            {"schemaVersion":1,"states":{
            "B1":{"cycleStartMillis":1,"cyclesToday":0,"cyclesDayStartMillis":1,"blockName":"A"},
            "BAD":{"cycleStartMillis":"oops","blockName":"B"},
            "B3":{"cycleStartMillis":1,"cyclesToday":2,"cyclesDayStartMillis":1,"blockName":"C"}}}
        """.trimIndent()

        val decoded = decodeStates(blob)

        assertEquals(setOf("B1", "B3"), decoded.keys)
        assertEquals(2, decoded.getValue("B3").cyclesToday)
    }

    @Test
    fun `a blob is lossy when it is non blank and unreadable as a whole or has a dropped entry`() {
        val encoded = BlockStateJsonMapper.encode(mapOf("B1" to full))
        val withMalformedEntry = """{"schemaVersion":1,"states":{"BAD":{"cycleStartMillis":"oops"}}}"""

        assertFalse(BlockStateJsonMapper.decode(null).isLossy)
        assertFalse(BlockStateJsonMapper.decode("  ").isLossy)
        assertFalse(BlockStateJsonMapper.decode(encoded).isLossy)
        assertTrue(BlockStateJsonMapper.decode("{not json").isLossy)
        assertTrue(BlockStateJsonMapper.decode("[]").isLossy)
        assertTrue(BlockStateJsonMapper.decode("""{"schemaVersion":1}""").isLossy)
        assertTrue(BlockStateJsonMapper.decode(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2")).isLossy)
        assertTrue(BlockStateJsonMapper.decode(withMalformedEntry).isLossy)
    }

    @Test
    fun `unknown keys are ignored`() {
        val blob = """
            {"schemaVersion":1,"extra":true,"states":{"B1":{"cycleStartMillis":1,"cyclesToday":0,
            "cyclesDayStartMillis":1,"blockName":"A","futureField":"x"}}}
        """.trimIndent()

        assertEquals(
            mapOf("B1" to BlockState(1L, null, 0, 1L, null, null, emptySet(), "A")),
            decodeStates(blob),
        )
    }

    @Test
    fun `an unknown hold reason keeps the hold until and packages and drops only the reason`() {
        // Fail-closed: HoldPolicy derives DAILY_CAP from a live holdUntil with a null reason.
        val blob = """
            {"schemaVersion":1,"states":{"B1":{"cycleStartMillis":1,"cyclesToday":3,"cyclesDayStartMillis":1,
            "holdUntilMillis":9000,"holdReason":"SOMETHING_NEW","holdPackageNames":["a","b"],"blockName":"A"}}}
        """.trimIndent()

        val decoded = decodeStates(blob).getValue("B1")

        assertEquals(null, decoded.holdReason)
        assertEquals(9_000L, decoded.holdUntilMillis)
        assertEquals(setOf("a", "b"), decoded.holdPackageNames)
        assertEquals(3, decoded.cyclesToday)
    }

    @Test
    fun `a missing hold reason decodes to null and keeps the hold`() {
        val blob = """
            {"schemaVersion":1,"states":{"B1":{"cycleStartMillis":1,"cyclesToday":0,"cyclesDayStartMillis":1,
            "holdUntilMillis":9000,"holdPackageNames":["a"],"blockName":"A"}}}
        """.trimIndent()

        val decoded = decodeStates(blob).getValue("B1")

        assertEquals(null, decoded.holdReason)
        assertEquals(9_000L, decoded.holdUntilMillis)
        assertEquals(setOf("a"), decoded.holdPackageNames)
    }
}

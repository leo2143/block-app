package com.leo.blockapp.adapters.output.ids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UuidIdGeneratorTest {

    private val generator = UuidIdGenerator()

    @Test
    fun `new ids are valid non blank UUIDs`() {
        val id = generator.newId()

        assertTrue(id.isNotBlank())
        assertEquals(id, UUID.fromString(id).toString())
    }

    @Test
    fun `new ids are unique`() {
        val ids = List(1000) { generator.newId() }

        assertEquals(1000, ids.toSet().size)
    }
}

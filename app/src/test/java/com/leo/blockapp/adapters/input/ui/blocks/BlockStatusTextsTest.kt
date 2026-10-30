package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.BlockListStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class BlockStatusTextsTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `a normal block has no line`() {
        assertNull(BlockStatusTexts.statusLine(BlockListStatus.Normal, utc))
    }

    @Test
    fun `a cooldown says when it ends`() {
        val until = 14 * hour + 30 * minute

        assertEquals("En enfriamiento hasta 14:30", BlockStatusTexts.statusLine(BlockListStatus.CoolingDown(until), utc))
    }

    @Test
    fun `the time is padded and uses 24 hours`() {
        assertEquals("En enfriamiento hasta 09:05", BlockStatusTexts.statusLine(BlockListStatus.CoolingDown(9 * hour + 5 * minute), utc))
        assertEquals("En enfriamiento hasta 23:59", BlockStatusTexts.statusLine(BlockListStatus.CoolingDown(23 * hour + 59 * minute), utc))
        assertEquals("En enfriamiento hasta 00:00", BlockStatusTexts.statusLine(BlockListStatus.CoolingDown(24 * hour), utc))
    }

    @Test
    fun `the time is shown in the given zone`() {
        val until = 14 * hour + 30 * minute

        assertEquals(
            "En enfriamiento hasta 16:30",
            BlockStatusTexts.statusLine(BlockListStatus.CoolingDown(until), ZoneOffset.ofHours(2)),
        )
    }

    @Test
    fun `a reached daily cap says until tomorrow`() {
        assertEquals("Bloqueado hasta mañana", BlockStatusTexts.statusLine(BlockListStatus.DailyCapReached(24 * hour), utc))
    }
}

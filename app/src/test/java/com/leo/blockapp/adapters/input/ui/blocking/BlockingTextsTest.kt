package com.leo.blockapp.adapters.input.ui.blocking

import com.leo.blockapp.domain.model.BlockReason
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockingTextsTest {

    private val second = 1_000L
    private val minute = 60 * second
    private val hour = 60 * minute

    @Test
    fun `the heading quotes the block name`() {
        assertEquals("«Redes» está bloqueado", blockingHeading("Redes"))
    }

    @Test
    fun `a blank or empty name falls back to the generic heading`() {
        assertEquals("App bloqueada", blockingHeading(""))
        assertEquals("App bloqueada", blockingHeading("   "))
    }

    @Test
    fun `the name is trimmed in the heading`() {
        assertEquals("«Redes» está bloqueado", blockingHeading("  Redes "))
    }

    @Test
    fun `a cooldown shows the countdown`() {
        assertEquals("Disponible en 35:00", blockingDetail(BlockReason.COOLDOWN, 35 * minute))
    }

    @Test
    fun `a daily cap shows come back tomorrow whatever the remaining time`() {
        assertEquals("Vuelve mañana", blockingDetail(BlockReason.DAILY_CAP, 5 * hour))
    }

    @Test
    fun `under an hour the countdown is mm ss`() {
        assertEquals("01:05", formatCountdown(65 * second))
        assertEquals("59:59", formatCountdown(hour - second))
    }

    @Test
    fun `from one hour the countdown is hh mm ss`() {
        assertEquals("01:00:00", formatCountdown(hour))
        assertEquals("01:02:05", formatCountdown(hour + 2 * minute + 5 * second))
    }

    @Test
    fun `a partial second rounds up so zero is shown only when the time is over`() {
        assertEquals("00:01", formatCountdown(1))
        assertEquals("01:00", formatCountdown(59 * second + 1))
        assertEquals("00:00", formatCountdown(0))
    }

    @Test
    fun `a negative remaining time is shown as zero`() {
        assertEquals("00:00", formatCountdown(-5 * second))
    }
}

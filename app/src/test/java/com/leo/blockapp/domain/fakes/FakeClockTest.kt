package com.leo.blockapp.domain.fakes

import org.junit.Assert.assertEquals
import org.junit.Test

class FakeClockTest {

    private val oneDayMillis = 24 * 60 * 60 * 1000L
    private val oneHourMillis = 60 * 60 * 1000L

    @Test
    fun `start of day floors the given timestamp to a 24h boundary`() {
        val clock = FakeClock()

        assertEquals(2 * oneDayMillis, clock.startOfDayMillis(2 * oneDayMillis + 5 * oneHourMillis))
    }

    @Test
    fun `start of day of a boundary timestamp is itself`() {
        assertEquals(oneDayMillis, FakeClock().startOfDayMillis(oneDayMillis))
    }

    @Test
    fun `one millisecond before a boundary belongs to the previous day`() {
        assertEquals(oneDayMillis, FakeClock().startOfDayMillis(2 * oneDayMillis - 1))
    }
}

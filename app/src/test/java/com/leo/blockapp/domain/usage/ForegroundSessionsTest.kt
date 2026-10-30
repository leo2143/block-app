package com.leo.blockapp.domain.usage

import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.model.UsageEventType.MOVED_TO_BACKGROUND
import com.leo.blockapp.domain.model.UsageEventType.MOVED_TO_FOREGROUND
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundSessionsTest {

    private val oneMinuteMillis = 60_000L
    private val oneHourMillis = 60 * oneMinuteMillis
    private val instagram = "com.instagram.android"
    private val x = "com.twitter.android"

    private fun foreground(packageName: String, timestampMillis: Long, activityName: String = "Main") =
        UsageEvent(packageName, activityName, MOVED_TO_FOREGROUND, timestampMillis)

    private fun background(packageName: String, timestampMillis: Long, activityName: String = "Main") =
        UsageEvent(packageName, activityName, MOVED_TO_BACKGROUND, timestampMillis)

    private fun sessions(
        vararg events: UsageEvent,
        rangeStartMillis: Long = 0L,
        rangeEndMillis: Long = 100 * oneMinuteMillis,
    ) = ForegroundSessions.fromEvents(events.toList(), rangeStartMillis, rangeEndMillis)

    @Test
    fun `a closed session keeps its package and bounds`() {
        val result = sessions(foreground(instagram, 10 * oneMinuteMillis), background(instagram, 15 * oneMinuteMillis))

        assertEquals(listOf(ForegroundSession(instagram, 10 * oneMinuteMillis, 15 * oneMinuteMillis)), result)
    }

    @Test
    fun `a session still open ends at the range end`() {
        val result = sessions(foreground(instagram, 90 * oneMinuteMillis))

        assertEquals(listOf(ForegroundSession(instagram, 90 * oneMinuteMillis, 100 * oneMinuteMillis)), result)
    }

    @Test
    fun `screens of the same app form one session`() {
        val result = sessions(
            foreground(instagram, 10 * oneMinuteMillis, "Feed"),
            foreground(instagram, 12 * oneMinuteMillis, "Reels"),
            background(instagram, 12 * oneMinuteMillis, "Feed"),
            background(instagram, 20 * oneMinuteMillis, "Reels"),
        )

        assertEquals(listOf(ForegroundSession(instagram, 10 * oneMinuteMillis, 20 * oneMinuteMillis)), result)
    }

    @Test
    fun `sessions of several apps are ordered by start`() {
        val result = sessions(
            foreground(x, 30 * oneMinuteMillis),
            background(x, 33 * oneMinuteMillis),
            foreground(instagram, 10 * oneMinuteMillis),
            background(instagram, 15 * oneMinuteMillis),
        )

        assertEquals(
            listOf(
                ForegroundSession(instagram, 10 * oneMinuteMillis, 15 * oneMinuteMillis),
                ForegroundSession(x, 30 * oneMinuteMillis, 33 * oneMinuteMillis),
            ),
            result,
        )
    }

    @Test
    fun `a session open across midnight is clipped to the start of the range`() {
        val midnight = 24 * oneHourMillis
        // Opened at 23:50 the day before: only its background event at 00:10 falls in the range.
        val result = sessions(
            foreground(instagram, midnight - 10 * oneMinuteMillis),
            background(instagram, midnight + 10 * oneMinuteMillis),
            rangeStartMillis = midnight,
            rangeEndMillis = midnight + oneHourMillis,
        )

        assertEquals(listOf(ForegroundSession(instagram, midnight, midnight + 10 * oneMinuteMillis)), result)
    }

    @Test
    fun `a session opened after midnight and still open counts to the range end`() {
        val midnight = 24 * oneHourMillis
        val result = sessions(
            foreground(instagram, midnight + 5 * oneMinuteMillis),
            rangeStartMillis = midnight,
            rangeEndMillis = midnight + oneHourMillis,
        )

        assertEquals(
            listOf(ForegroundSession(instagram, midnight + 5 * oneMinuteMillis, midnight + oneHourMillis)),
            result,
        )
    }

    @Test
    fun `no events means no sessions`() {
        assertEquals(emptyList<ForegroundSession>(), sessions())
    }
}

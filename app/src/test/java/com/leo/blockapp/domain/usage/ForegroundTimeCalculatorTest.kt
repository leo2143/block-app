package com.leo.blockapp.domain.usage

import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.model.UsageEventType.MOVED_TO_BACKGROUND
import com.leo.blockapp.domain.model.UsageEventType.MOVED_TO_FOREGROUND
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundTimeCalculatorTest {

    private val oneMinuteMillis = 60_000L
    private val instagramPackage = "com.instagram.android"
    private val xPackage = "com.twitter.android"

    private fun movedToForeground(packageName: String, timestampMillis: Long, activityName: String = "Main") =
        UsageEvent(packageName, activityName, MOVED_TO_FOREGROUND, timestampMillis)

    private fun movedToBackground(packageName: String, timestampMillis: Long, activityName: String = "Main") =
        UsageEvent(packageName, activityName, MOVED_TO_BACKGROUND, timestampMillis)

    private fun calculate(
        vararg events: UsageEvent,
        rangeStartMillis: Long = 0L,
        rangeEndMillis: Long = 100 * oneMinuteMillis,
    ) = ForegroundTimeCalculator.calculateForegroundMillis(events.toList(), rangeStartMillis, rangeEndMillis)

    @Test
    fun `single session`() {
        val foregroundMillis = calculate(
            movedToForeground(instagramPackage, 10 * oneMinuteMillis),
            movedToBackground(instagramPackage, 15 * oneMinuteMillis),
        )
        assertEquals(mapOf(instagramPackage to 5 * oneMinuteMillis), foregroundMillis)
    }

    @Test
    fun `sessions of the same app are summed`() {
        val foregroundMillis = calculate(
            movedToForeground(instagramPackage, 10 * oneMinuteMillis),
            movedToBackground(instagramPackage, 15 * oneMinuteMillis),
            movedToForeground(instagramPackage, 30 * oneMinuteMillis),
            movedToBackground(instagramPackage, 33 * oneMinuteMillis),
        )
        assertEquals(8 * oneMinuteMillis, foregroundMillis[instagramPackage])
    }

    @Test
    fun `app still open counts until the end of the range`() {
        val foregroundMillis = calculate(movedToForeground(instagramPackage, 90 * oneMinuteMillis))
        assertEquals(10 * oneMinuteMillis, foregroundMillis[instagramPackage])
    }

    @Test
    fun `app already open before the range counts from the range start`() {
        val foregroundMillis = calculate(
            movedToBackground(instagramPackage, 25 * oneMinuteMillis),
            rangeStartMillis = 20 * oneMinuteMillis,
        )
        assertEquals(5 * oneMinuteMillis, foregroundMillis[instagramPackage])
    }

    @Test
    fun `switching screens within the same app neither splits nor double counts time`() {
        val foregroundMillis = calculate(
            movedToForeground(instagramPackage, 10 * oneMinuteMillis, "Feed"),
            movedToForeground(instagramPackage, 12 * oneMinuteMillis, "Reels"),
            movedToBackground(instagramPackage, 12 * oneMinuteMillis, "Feed"),
            movedToBackground(instagramPackage, 20 * oneMinuteMillis, "Reels"),
        )
        assertEquals(10 * oneMinuteMillis, foregroundMillis[instagramPackage])
    }

    @Test
    fun `each app is counted separately`() {
        val foregroundMillis = calculate(
            movedToForeground(instagramPackage, 10 * oneMinuteMillis),
            movedToBackground(instagramPackage, 15 * oneMinuteMillis),
            movedToForeground(xPackage, 15 * oneMinuteMillis),
            movedToBackground(xPackage, 18 * oneMinuteMillis),
        )
        assertEquals(mapOf(instagramPackage to 5 * oneMinuteMillis, xPackage to 3 * oneMinuteMillis), foregroundMillis)
    }

    @Test
    fun `unordered events are sorted by timestamp`() {
        val foregroundMillis = calculate(
            movedToBackground(instagramPackage, 15 * oneMinuteMillis),
            movedToForeground(instagramPackage, 10 * oneMinuteMillis),
        )
        assertEquals(5 * oneMinuteMillis, foregroundMillis[instagramPackage])
    }

    @Test
    fun `events outside the range are ignored`() {
        val foregroundMillis = calculate(
            movedToForeground(instagramPackage, 1 * oneMinuteMillis),
            movedToBackground(instagramPackage, 2 * oneMinuteMillis),
            movedToForeground(instagramPackage, 10 * oneMinuteMillis),
            movedToBackground(instagramPackage, 15 * oneMinuteMillis),
            rangeStartMillis = 5 * oneMinuteMillis,
        )
        assertEquals(5 * oneMinuteMillis, foregroundMillis[instagramPackage])
    }

    @Test
    fun `no events means no usage`() {
        assertEquals(emptyMap<String, Long>(), calculate())
    }
}

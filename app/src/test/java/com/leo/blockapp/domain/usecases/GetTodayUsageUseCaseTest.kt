package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.AppUsage
import com.leo.blockapp.domain.model.InstalledApp
import com.leo.blockapp.domain.model.TodayUsageResult
import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.model.UsageEventType
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.ports.output.InstalledAppCatalog
import com.leo.blockapp.domain.ports.output.SystemPermissions
import com.leo.blockapp.domain.ports.output.UsageEventSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Real adapters are replaced by fakes, so the use case is tested without a phone. */
class GetTodayUsageUseCaseTest {

    private val oneMinuteMillis = 60_000L
    private val midnightMillis = 1_000 * oneMinuteMillis
    private val instagram = InstalledApp("com.instagram.android", "Instagram")
    private val x = InstalledApp("com.twitter.android", "X")
    private val whatsapp = InstalledApp("com.whatsapp", "WhatsApp")

    private class FakeUsageEventSource(private val events: List<UsageEvent>) : UsageEventSource {
        var requestedRangeMillis: Pair<Long, Long>? = null
        override suspend fun getEvents(fromMillis: Long, toMillis: Long): List<UsageEvent> {
            requestedRangeMillis = fromMillis to toMillis
            return events
        }
    }

    private class FakeInstalledAppCatalog(private val launchableApps: List<InstalledApp>) : InstalledAppCatalog {
        override suspend fun getLaunchableApps() = launchableApps
    }

    private class FakeSystemPermissions(private val usageAccessGranted: Boolean) : SystemPermissions {
        override fun hasUsageAccess() = usageAccessGranted
    }

    private inner class FakeClock(private val nowMillis: Long) : Clock {
        override fun nowMillis() = nowMillis
        override fun startOfDayMillis(timestampMillis: Long) = midnightMillis
    }

    private fun usageEvent(app: InstalledApp, type: UsageEventType, minutesAfterMidnight: Long) =
        UsageEvent(app.packageName, "Main", type, midnightMillis + minutesAfterMidnight * oneMinuteMillis)

    @Test
    fun `without usage access it does not query events`() = runTest {
        val usageEventSource = FakeUsageEventSource(emptyList())
        val getTodayUsage = GetTodayUsageUseCase(
            usageEventSource,
            FakeInstalledAppCatalog(listOf(instagram)),
            FakeSystemPermissions(usageAccessGranted = false),
            FakeClock(midnightMillis),
        )

        assertEquals(TodayUsageResult.MissingUsageAccess, getTodayUsage())
        assertNull(usageEventSource.requestedRangeMillis)
    }

    @Test
    fun `queries events from midnight until now`() = runTest {
        val nowMillis = midnightMillis + 90 * oneMinuteMillis
        val usageEventSource = FakeUsageEventSource(emptyList())
        val getTodayUsage = GetTodayUsageUseCase(
            usageEventSource,
            FakeInstalledAppCatalog(emptyList()),
            FakeSystemPermissions(usageAccessGranted = true),
            FakeClock(nowMillis),
        )

        getTodayUsage()

        assertEquals(midnightMillis to nowMillis, usageEventSource.requestedRangeMillis)
    }

    @Test
    fun `sorts by usage and lists unused apps with zero`() = runTest {
        val usageEventSource = FakeUsageEventSource(
            listOf(
                usageEvent(x, UsageEventType.MOVED_TO_FOREGROUND, 10),
                usageEvent(x, UsageEventType.MOVED_TO_BACKGROUND, 13),
                usageEvent(instagram, UsageEventType.MOVED_TO_FOREGROUND, 20),
                usageEvent(instagram, UsageEventType.MOVED_TO_BACKGROUND, 30),
            ),
        )
        val getTodayUsage = GetTodayUsageUseCase(
            usageEventSource,
            FakeInstalledAppCatalog(listOf(whatsapp, x, instagram)),
            FakeSystemPermissions(usageAccessGranted = true),
            FakeClock(midnightMillis + 60 * oneMinuteMillis),
        )

        val expectedResult = TodayUsageResult.Available(
            listOf(
                AppUsage(instagram, 10 * oneMinuteMillis),
                AppUsage(x, 3 * oneMinuteMillis),
                AppUsage(whatsapp, 0L),
            ),
        )
        assertEquals(expectedResult, getTodayUsage())
    }
}

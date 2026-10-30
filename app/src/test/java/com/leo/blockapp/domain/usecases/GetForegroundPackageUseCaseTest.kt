package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeClock
import com.leo.blockapp.domain.fakes.FakeUsageEventSource
import com.leo.blockapp.domain.model.ForegroundApp
import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.model.UsageEventType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GetForegroundPackageUseCaseTest {

    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val startOfToday = 10 * day
    private val now = startOfToday + 12 * 60 * minute
    private val clock = FakeClock(nowMillis = now)

    private val instagram = "com.instagram.android"
    private val launcher = "com.android.launcher"

    private fun foreground(packageName: String, minutesAgo: Long, activity: String = "Main") =
        UsageEvent(packageName, activity, UsageEventType.MOVED_TO_FOREGROUND, now - minutesAgo * minute)

    private fun background(packageName: String, minutesAgo: Long, activity: String = "Main") =
        UsageEvent(packageName, activity, UsageEventType.MOVED_TO_BACKGROUND, now - minutesAgo * minute)

    private fun useCaseWith(vararg events: UsageEvent): Pair<GetForegroundPackageUseCase, FakeUsageEventSource> {
        val source = FakeUsageEventSource(events.toList())
        return GetForegroundPackageUseCase(source, clock) to source
    }

    @Test
    fun `no events means no known foreground package`() = runTest {
        val (useCase, _) = useCaseWith()

        assertNull(useCase())
    }

    @Test
    fun `the package whose latest foreground has no later background is the foreground one`() = runTest {
        val (useCase, _) = useCaseWith(
            foreground(launcher, 30),
            background(launcher, 20),
            foreground(instagram, 20),
        )

        assertEquals(ForegroundApp(instagram, now - 20 * minute), useCase())
    }

    @Test
    fun `a package that went to the background is not foreground`() = runTest {
        val (useCase, _) = useCaseWith(foreground(instagram, 20), background(instagram, 5))

        assertNull(useCase())
    }

    @Test
    fun `the latest foreground wins when two packages are open`() = runTest {
        val (useCase, _) = useCaseWith(foreground(launcher, 20), foreground(instagram, 10))

        assertEquals(ForegroundApp(instagram, now - 10 * minute), useCase())
    }

    @Test
    fun `moving between activities of one app keeps it foreground whatever the event order`() = runTest {
        val (useCase, _) = useCaseWith(
            foreground(instagram, 20, activity = "Feed"),
            foreground(instagram, 10, activity = "Reels"),
            background(instagram, 9, activity = "Feed"),
        )

        assertEquals(instagram, useCase()?.packageName)
    }

    @Test
    fun `the foreground since is the opening event of the latest open activity`() = runTest {
        val (useCase, _) = useCaseWith(
            foreground(instagram, 20, activity = "Feed"),
            foreground(instagram, 10, activity = "Reels"),
            background(instagram, 9, activity = "Feed"),
        )

        assertEquals(now - 10 * minute, useCase()?.foregroundSinceMillis)
    }

    @Test
    fun `an app open since yesterday is still found`() = runTest {
        val (useCase, source) = useCaseWith(foreground(instagram, 12 * 60 + 30))

        assertEquals(ForegroundApp(instagram, now - (12 * 60 + 30) * minute), useCase())
        assertEquals(listOf((now - 10 * minute) to now, (startOfToday - day) to now), source.requests)
    }

    @Test
    fun `a recent switch is answered from the first 10 minute window with one query`() = runTest {
        val (useCase, source) = useCaseWith(foreground(launcher, 30), foreground(instagram, 2))

        assertEquals(instagram, useCase()?.packageName)
        assertEquals(listOf((now - 10 * minute) to now), source.requests)
    }

    @Test
    fun `nothing open costs two queries, the recent window and the full one`() = runTest {
        val (useCase, source) = useCaseWith()

        assertNull(useCase())
        assertEquals(listOf((now - 10 * minute) to now, (startOfToday - day) to now), source.requests)
    }

    @Test
    fun `a session older than the full window is not found`() = runTest {
        val (useCase, source) = useCaseWith(foreground(instagram, 12 * 60 + 24 * 60 + 1))

        assertNull(useCase())
        assertEquals(2, source.requests.size)
    }

    @Test
    fun `an app still open behind a background of another one is found by widening`() = runTest {
        val (useCase, _) = useCaseWith(
            foreground(instagram, 60, activity = "Feed"),
            foreground(launcher, 20),
            background(launcher, 5),
        )

        assertEquals(instagram, useCase()?.packageName)
    }

    @Test
    fun `events are sorted by time before they are read`() = runTest {
        val (useCase, _) = useCaseWith(background(instagram, 5), foreground(launcher, 3), foreground(instagram, 10))

        assertEquals(launcher, useCase()?.packageName)
    }
}

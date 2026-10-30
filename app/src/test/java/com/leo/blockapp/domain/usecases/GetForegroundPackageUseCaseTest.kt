package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeClock
import com.leo.blockapp.domain.fakes.FakeUsageEventSource
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

        assertEquals(instagram, useCase())
    }

    @Test
    fun `a package that went to the background is not foreground`() = runTest {
        val (useCase, _) = useCaseWith(foreground(instagram, 20), background(instagram, 5))

        assertNull(useCase())
    }

    @Test
    fun `the latest foreground wins when two packages are open`() = runTest {
        val (useCase, _) = useCaseWith(foreground(launcher, 20), foreground(instagram, 10))

        assertEquals(instagram, useCase())
    }

    @Test
    fun `moving between activities of one app keeps it foreground whatever the event order`() = runTest {
        val (useCase, _) = useCaseWith(
            foreground(instagram, 20, activity = "Feed"),
            foreground(instagram, 10, activity = "Reels"),
            background(instagram, 9, activity = "Feed"),
        )

        assertEquals(instagram, useCase())
    }

    @Test
    fun `an app open since yesterday is still found`() = runTest {
        val (useCase, source) = useCaseWith(foreground(instagram, 12 * 60 + 30))

        assertEquals(instagram, useCase())
        assertEquals(listOf((startOfToday - day) to now), source.requests)
    }

    @Test
    fun `events are sorted by time before they are read`() = runTest {
        val (useCase, _) = useCaseWith(background(instagram, 5), foreground(launcher, 3), foreground(instagram, 10))

        assertEquals(launcher, useCase())
    }
}

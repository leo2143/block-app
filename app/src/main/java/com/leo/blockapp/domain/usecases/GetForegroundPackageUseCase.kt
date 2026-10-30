package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.ForegroundApp
import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.model.UsageEventType
import com.leo.blockapp.domain.ports.input.GetForegroundPackage
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.ports.output.UsageEventSource

/**
 * The package whose latest MOVED_TO_FOREGROUND has no later MOVED_TO_BACKGROUND, else null. Activities are tracked
 * one by one, so moving between two activities of an app never makes the app look backgrounded, whatever the order
 * of the two events.
 *
 * It also reports when that app came to the foreground (the timestamp of the opening event of its latest open
 * activity), the evidence [EvaluateForegroundAppUseCase] uses to count an app opened before its usage window.
 *
 * The events are queried in two windows (last 10 minutes, then back to the start of the previous day) and the first
 * window that finds an open activity answers. The latest open foreground in the narrow window is also the latest in
 * the full one, so the answer is the same as querying the full window; only the cost changes, since the usual case
 * (a recent app switch) never reads a day of events. When nothing is open both queries run, but the enforcer
 * suspends polling while the screen is off or keyguarded (slice 6), which is the common "nothing open" case.
 *
 * Limits: it reports a single foreground package (multi-window is not covered), and a session older than the full
 * window returns null.
 */
class GetForegroundPackageUseCase(
    private val usageEventSource: UsageEventSource,
    private val clock: Clock,
) : GetForegroundPackage {

    override suspend fun invoke(): ForegroundApp? {
        val nowMillis = clock.nowMillis()
        val fullWindowStartMillis = clock.startOfDayMillis(nowMillis) - ONE_DAY_MILLIS
        val windowStarts = listOf(nowMillis - RECENT_WINDOW_MILLIS).filter { it > fullWindowStartMillis } +
            fullWindowStartMillis
        for (fromMillis in windowStarts) {
            latestOpenApp(usageEventSource.getEvents(fromMillis, nowMillis))?.let { return it }
        }
        return null
    }

    private fun latestOpenApp(events: List<UsageEvent>): ForegroundApp? {
        val foregroundSinceByActivity = mutableMapOf<Pair<String, String>, Long>()
        events.sortedBy { it.timestampMillis }.forEach { event ->
            val activity = event.packageName to event.activityName
            when (event.type) {
                UsageEventType.MOVED_TO_FOREGROUND -> foregroundSinceByActivity[activity] = event.timestampMillis
                UsageEventType.MOVED_TO_BACKGROUND -> foregroundSinceByActivity.remove(activity)
            }
        }
        return foregroundSinceByActivity.maxByOrNull { it.value }?.let { ForegroundApp(it.key.first, it.value) }
    }

    private companion object {
        const val ONE_DAY_MILLIS = 24 * 60 * 60 * 1000L
        const val RECENT_WINDOW_MILLIS = 10 * 60 * 1000L
    }
}

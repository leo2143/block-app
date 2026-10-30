package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.UsageEventType
import com.leo.blockapp.domain.ports.input.GetForegroundPackage
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.ports.output.UsageEventSource

/**
 * The package whose latest MOVED_TO_FOREGROUND has no later MOVED_TO_BACKGROUND, else null. Activities are tracked
 * one by one, so moving between two activities of an app never makes the app look backgrounded, whatever the order
 * of the two events. The query reaches back to the start of the previous day so an app that has been open for hours
 * on one screen is still found.
 */
class GetForegroundPackageUseCase(
    private val usageEventSource: UsageEventSource,
    private val clock: Clock,
) : GetForegroundPackage {

    override suspend fun invoke(): String? {
        val nowMillis = clock.nowMillis()
        val fromMillis = clock.startOfDayMillis(nowMillis) - ONE_DAY_MILLIS
        val foregroundSinceByActivity = mutableMapOf<Pair<String, String>, Long>()
        usageEventSource.getEvents(fromMillis, nowMillis).sortedBy { it.timestampMillis }.forEach { event ->
            val activity = event.packageName to event.activityName
            when (event.type) {
                UsageEventType.MOVED_TO_FOREGROUND -> foregroundSinceByActivity[activity] = event.timestampMillis
                UsageEventType.MOVED_TO_BACKGROUND -> foregroundSinceByActivity.remove(activity)
            }
        }
        return foregroundSinceByActivity.maxByOrNull { it.value }?.key?.first
    }

    private companion object {
        const val ONE_DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}

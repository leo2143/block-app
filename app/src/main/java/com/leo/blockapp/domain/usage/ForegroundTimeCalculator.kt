package com.leo.blockapp.domain.usage

import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.model.UsageEventType

/**
 * Turns foreground/background events into foreground time per app.
 *
 * An app is in the foreground while at least one of its activities is, so moving between
 * screens of the same app neither splits nor double-counts its time.
 */
object ForegroundTimeCalculator {

    /** Milliseconds each app spent in the foreground within [rangeStartMillis, rangeEndMillis]. */
    fun calculateForegroundMillis(
        events: List<UsageEvent>,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
    ): Map<String, Long> {
        val foregroundMillisByPackage = mutableMapOf<String, Long>()
        val foregroundActivitiesByPackage = mutableMapOf<String, MutableSet<String>>()
        val sessionStartByPackage = mutableMapOf<String, Long>()
        val packagesSeen = mutableSetOf<String>()

        fun addForegroundTime(packageName: String, startMillis: Long, endMillis: Long) {
            if (endMillis > startMillis) {
                foregroundMillisByPackage[packageName] =
                    (foregroundMillisByPackage[packageName] ?: 0L) + (endMillis - startMillis)
            }
        }

        for (event in events.sortedBy { it.timestampMillis }) {
            if (event.timestampMillis < rangeStartMillis || event.timestampMillis > rangeEndMillis) continue
            val foregroundActivities = foregroundActivitiesByPackage.getOrPut(event.packageName) { mutableSetOf() }
            val isFirstEventForPackage = packagesSeen.add(event.packageName)

            when (event.type) {
                UsageEventType.MOVED_TO_FOREGROUND -> {
                    if (foregroundActivities.isEmpty()) sessionStartByPackage[event.packageName] = event.timestampMillis
                    foregroundActivities += event.activityName
                }
                UsageEventType.MOVED_TO_BACKGROUND -> {
                    if (foregroundActivities.remove(event.activityName)) {
                        if (foregroundActivities.isEmpty()) {
                            val sessionStart = sessionStartByPackage.remove(event.packageName)!!
                            addForegroundTime(event.packageName, sessionStart, event.timestampMillis)
                        }
                    } else if (isFirstEventForPackage) {
                        // It left the foreground before we saw it arrive: it was already open before the range started.
                        addForegroundTime(event.packageName, rangeStartMillis, event.timestampMillis)
                    }
                }
            }
        }

        // Apps still in the foreground count until the end of the range.
        sessionStartByPackage.forEach { (packageName, sessionStart) ->
            addForegroundTime(packageName, sessionStart, rangeEndMillis)
        }
        return foregroundMillisByPackage
    }
}

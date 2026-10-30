package com.leo.blockapp.domain.usage

import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.model.UsageEventType

/** One continuous stretch of [packageName] in the foreground, from [startMillis] to [endMillis]. */
data class ForegroundSession(val packageName: String, val startMillis: Long, val endMillis: Long)

/**
 * Turns foreground/background events into sessions, the building block of [ForegroundTimeCalculator].
 *
 * An app is in the foreground while at least one of its activities is. A session that was already open when the
 * range started (its first event is a background one) starts at the range start, so one open across midnight is
 * clipped to the day; a session still open at the end of the range ends at the range end.
 */
object ForegroundSessions {

    /** Sessions within [rangeStartMillis, rangeEndMillis], ordered by start time. */
    fun fromEvents(
        events: List<UsageEvent>,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
    ): List<ForegroundSession> {
        val sessions = mutableListOf<ForegroundSession>()
        val foregroundActivitiesByPackage = mutableMapOf<String, MutableSet<String>>()
        val sessionStartByPackage = mutableMapOf<String, Long>()
        val packagesSeen = mutableSetOf<String>()

        fun addSession(packageName: String, startMillis: Long, endMillis: Long) {
            if (endMillis > startMillis) sessions += ForegroundSession(packageName, startMillis, endMillis)
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
                            addSession(event.packageName, sessionStart, event.timestampMillis)
                        }
                    } else if (isFirstEventForPackage) {
                        // It left the foreground before we saw it arrive: it was already open before the range started.
                        addSession(event.packageName, rangeStartMillis, event.timestampMillis)
                    }
                }
            }
        }

        // Apps still in the foreground count until the end of the range.
        sessionStartByPackage.forEach { (packageName, sessionStart) ->
            addSession(packageName, sessionStart, rangeEndMillis)
        }
        return sessions.sortedBy { it.startMillis }
    }
}

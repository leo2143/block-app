package com.leo.blockapp.domain.usage

import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.model.UsageEventType

/** One continuous stretch of [packageName] in the foreground, from [startMillis] to [endMillis]. */
data class ForegroundSession(val packageName: String, val startMillis: Long, val endMillis: Long)

/**
 * Turns foreground/background events into sessions, the building block of [ForegroundTimeCalculator].
 *
 * An app is in the foreground while at least one of its activities is. [fromEvents] accepts events from before the
 * range: they are replayed to know which activities were open when the range started, and those start at the range
 * start, so an app opened before midnight and kept on one screen counts from midnight. Without earlier events, an
 * activity whose first in-range event is a background one is taken as open since the range start too (a session
 * older than the queried window). A session still open at the end of the range ends at the range end.
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

        fun addSession(packageName: String, startMillis: Long, endMillis: Long) {
            if (endMillis > startMillis) sessions += ForegroundSession(packageName, startMillis, endMillis)
        }

        val sortedEvents = events.filter { it.timestampMillis <= rangeEndMillis }.sortedBy { it.timestampMillis }
        val preRangeEvents = sortedEvents.filter { it.timestampMillis < rangeStartMillis }
        val inRangeEvents = sortedEvents.filter { it.timestampMillis >= rangeStartMillis }

        // The activities still open at the range start, replayed from the events before it.
        val seenActivities = mutableSetOf<Pair<String, String>>()
        for (event in preRangeEvents) {
            seenActivities += event.packageName to event.activityName
            val openActivities = foregroundActivitiesByPackage.getOrPut(event.packageName) { mutableSetOf() }
            when (event.type) {
                UsageEventType.MOVED_TO_FOREGROUND -> openActivities += event.activityName
                UsageEventType.MOVED_TO_BACKGROUND -> openActivities -= event.activityName
            }
        }
        foregroundActivitiesByPackage.forEach { (packageName, openActivities) ->
            if (openActivities.isNotEmpty()) sessionStartByPackage[packageName] = rangeStartMillis
        }

        // An activity with no earlier event whose first in-range event is a background one was already open.
        for (event in inRangeEvents) {
            if (!seenActivities.add(event.packageName to event.activityName) ||
                event.type != UsageEventType.MOVED_TO_BACKGROUND
            ) {
                continue
            }
            foregroundActivitiesByPackage.getOrPut(event.packageName) { mutableSetOf() } += event.activityName
            sessionStartByPackage[event.packageName] = rangeStartMillis
        }

        for (event in inRangeEvents) {
            val foregroundActivities = foregroundActivitiesByPackage.getOrPut(event.packageName) { mutableSetOf() }

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

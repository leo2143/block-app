package com.leo.blockapp.domain.usage

import com.leo.blockapp.domain.model.UsageEvent

/**
 * Turns foreground/background events into foreground time per app.
 *
 * An app is in the foreground while at least one of its activities is, so moving between
 * screens of the same app neither splits nor double-counts its time. The session logic lives in
 * [ForegroundSessions]; this sums it per app.
 */
object ForegroundTimeCalculator {

    /** Milliseconds each app spent in the foreground within [rangeStartMillis, rangeEndMillis]. */
    fun calculateForegroundMillis(
        events: List<UsageEvent>,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
    ): Map<String, Long> =
        ForegroundSessions.fromEvents(events, rangeStartMillis, rangeEndMillis)
            .groupBy { it.packageName }
            .mapValues { (_, sessions) -> sessions.sumOf { it.endMillis - it.startMillis } }
}

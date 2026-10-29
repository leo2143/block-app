package com.leo.blockapp.domain.ports.output

import com.leo.blockapp.domain.model.UsageEvent

/** Output port: where raw usage events come from (on Android, UsageStatsManager). */
interface UsageEventSource {
    suspend fun getEvents(fromMillis: Long, toMillis: Long): List<UsageEvent>
}

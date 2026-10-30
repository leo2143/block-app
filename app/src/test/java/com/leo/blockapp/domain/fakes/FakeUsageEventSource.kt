package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.ports.output.UsageEventSource
import java.io.IOException

/**
 * In-memory usage events. Like the real source it returns only the events inside the requested range, and it
 * records every request in [requests]. Set [failure] to make every query throw.
 */
class FakeUsageEventSource(
    var events: List<UsageEvent> = emptyList(),
) : UsageEventSource {

    private val recordedRequests = mutableListOf<Pair<Long, Long>>()

    val requests: List<Pair<Long, Long>> get() = recordedRequests.toList()

    @Volatile
    var failure: IOException? = null

    override suspend fun getEvents(fromMillis: Long, toMillis: Long): List<UsageEvent> {
        recordedRequests += fromMillis to toMillis
        failure?.let { throw it }
        return events.filter { it.timestampMillis in fromMillis..toMillis }
    }
}

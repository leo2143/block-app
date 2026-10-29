package com.leo.blockapp.adapters.output.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.leo.blockapp.domain.model.UsageEvent
import com.leo.blockapp.domain.model.UsageEventType
import com.leo.blockapp.domain.ports.output.UsageEventSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Output adapter: maps UsageStatsManager events to the domain model. */
class UsageStatsEventSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : UsageEventSource {

    override suspend fun getEvents(fromMillis: Long, toMillis: Long): List<UsageEvent> =
        withContext(Dispatchers.IO) {
            val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
            val systemEvents = usageStatsManager.queryEvents(fromMillis, toMillis)
            val currentSystemEvent = UsageEvents.Event()
            val usageEvents = mutableListOf<UsageEvent>()

            while (systemEvents.hasNextEvent()) {
                systemEvents.getNextEvent(currentSystemEvent)
                val eventType = when (currentSystemEvent.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventType.MOVED_TO_FOREGROUND
                    UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventType.MOVED_TO_BACKGROUND
                    else -> continue
                }
                usageEvents += UsageEvent(
                    packageName = currentSystemEvent.packageName,
                    activityName = currentSystemEvent.className.orEmpty(),
                    type = eventType,
                    timestampMillis = currentSystemEvent.timeStamp,
                )
            }
            usageEvents
        }
}

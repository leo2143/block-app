package com.leo.blockapp.adapters.output.clock

import com.leo.blockapp.domain.ports.output.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

class DeviceClock @Inject constructor() : Clock {

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun startOfDayMillis(timestampMillis: Long): Long {
        val deviceZone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(timestampMillis).atZone(deviceZone).toLocalDate()
            .atStartOfDay(deviceZone).toInstant().toEpochMilli()
    }
}

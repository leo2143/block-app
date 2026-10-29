package com.leo.blockapp.domain.ports.output

/** Output port: the current time. Tests replace it with a fake clock. */
interface Clock {
    fun nowMillis(): Long

    /** Local midnight of the day that contains [timestampMillis]. */
    fun startOfDayMillis(timestampMillis: Long): Long
}

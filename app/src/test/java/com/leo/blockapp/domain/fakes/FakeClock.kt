package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.ports.output.Clock

/** Test clock: [nowMillis] is settable; the start of day is the fixed [startOfDayMillis] unless overridden. */
class FakeClock(
    var nowMillis: Long = 0L,
    private val startOfDayMillis: Long = 0L,
) : Clock {
    override fun nowMillis() = nowMillis

    override fun startOfDayMillis(timestampMillis: Long) = startOfDayMillis
}

package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.ports.output.Clock

/** Test clock: [nowMillis] is settable; days are 24 h blocks aligned to the epoch (UTC, no DST). */
class FakeClock(
    var nowMillis: Long = 0L,
) : Clock {
    private val oneDayMillis = 24 * 60 * 60 * 1000L

    override fun nowMillis() = nowMillis

    override fun startOfDayMillis(timestampMillis: Long) = timestampMillis - Math.floorMod(timestampMillis, oneDayMillis)
}

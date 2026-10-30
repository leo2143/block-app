package com.leo.blockapp.adapters.input.ui.blocks

import com.leo.blockapp.domain.model.BlockListStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Spanish status line shown under a block in the list while a hold is active. */
internal object BlockStatusTexts {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** The line for [status], or null when the block has nothing to say. The time is shown in [zone]. */
    fun statusLine(status: BlockListStatus, zone: ZoneId = ZoneId.systemDefault()): String? = when (status) {
        BlockListStatus.Normal -> null
        is BlockListStatus.CoolingDown -> "En enfriamiento hasta ${clockTime(status.untilMillis, zone)}"
        is BlockListStatus.DailyCapReached -> "Bloqueado hasta mañana"
    }

    private fun clockTime(epochMillis: Long, zone: ZoneId): String =
        timeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}

package com.leo.blockapp.adapters.input.ui.blocking

import com.leo.blockapp.domain.model.BlockReason
import java.util.Locale

private const val GENERIC_HEADING = "App bloqueada"
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** Heading of the block screen; a null-ish or blank name (deleted blocks may have none) falls back to a generic one. */
internal fun blockingHeading(blockName: String): String {
    val name = blockName.trim()
    return if (name.isEmpty()) GENERIC_HEADING else "«$name» está bloqueado"
}

/** Second line of the block screen: a countdown for a cooldown, "come back tomorrow" for the daily cap. */
internal fun blockingDetail(reason: BlockReason, remainingMillis: Long): String = when (reason) {
    BlockReason.COOLDOWN -> "Disponible en ${formatCountdown(remainingMillis)}"
    BlockReason.DAILY_CAP -> "Vuelve mañana"
}

/** `mm:ss`, or `hh:mm:ss` from one hour. Partial seconds round up so `00:00` means the time is really over. */
internal fun formatCountdown(remainingMillis: Long): String {
    val totalSeconds = (remainingMillis.coerceAtLeast(0) + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}

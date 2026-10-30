package com.leo.blockapp.domain.model

/**
 * Persisted enforcement state of one block (instants are epoch millis).
 *
 * A HOLD ([holdUntilMillis], [holdReason], [holdPackageNames]) is a floor that rule edits never shorten: it is
 * active only while `holdUntilMillis > now`. [blockName] is kept so a hold of a deleted block can still be shown.
 */
data class BlockState(
    val cycleStartMillis: Long,
    val cooldownEndMillis: Long?,
    val cyclesToday: Int,
    val cyclesDayStartMillis: Long,
    val holdUntilMillis: Long?,
    val holdReason: BlockReason?,
    val holdPackageNames: Set<String>,
    val blockName: String,
) {
    companion object {
        /** A fresh state: the cycle starts with the day, no cooldown and no hold. */
        fun initial(blockName: String, startOfTodayMillis: Long) = BlockState(
            cycleStartMillis = startOfTodayMillis,
            cooldownEndMillis = null,
            cyclesToday = 0,
            cyclesDayStartMillis = startOfTodayMillis,
            holdUntilMillis = null,
            holdReason = null,
            holdPackageNames = emptySet(),
            blockName = blockName,
        )
    }
}

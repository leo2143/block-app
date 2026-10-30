package com.leo.blockapp.domain.model

/** What the blocks list says about one block. Present only while a hold is active. */
sealed interface BlockListStatus {
    /** Nothing holds the block. The list shows no line. */
    data object Normal : BlockListStatus

    /** A cooldown is running until [untilMillis]. */
    data class CoolingDown(val untilMillis: Long) : BlockListStatus

    /** The daily cap is reached; the block stays blocked until [untilMillis] (the next day). */
    data class DailyCapReached(val untilMillis: Long) : BlockListStatus
}

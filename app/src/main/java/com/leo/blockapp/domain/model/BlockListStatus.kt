package com.leo.blockapp.domain.model

/** What the blocks list says about one block. Present only while a hold is active. */
sealed interface BlockListStatus {
    /**
     * True while a daily-cap hold is live for the block, even when a running cooldown is what is displayed. A cap
     * change made now does not lift that hold today.
     */
    val dailyCapPending: Boolean

    /** Nothing holds the block. The list shows no line. */
    data object Normal : BlockListStatus {
        override val dailyCapPending: Boolean = false
    }

    /**
     * A cooldown is running until [untilMillis]. [dailyCapPending] tells that a longer daily-cap hold is stored and
     * takes over when the cooldown ends.
     */
    data class CoolingDown(
        val untilMillis: Long,
        override val dailyCapPending: Boolean = false,
    ) : BlockListStatus

    /** The daily cap is reached; the block stays blocked until [untilMillis] (the next day). */
    data class DailyCapReached(val untilMillis: Long) : BlockListStatus {
        override val dailyCapPending: Boolean get() = true
    }
}

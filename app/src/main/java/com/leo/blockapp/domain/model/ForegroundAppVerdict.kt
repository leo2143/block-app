package com.leo.blockapp.domain.model

/** What to do with the app that just came to the foreground. */
sealed interface ForegroundAppVerdict {
    /** The app is in no enabled block and no active hold covers it: nothing to enforce. */
    data object NotEnforced : ForegroundAppVerdict

    /** The app of block [blockId] may be used for [remainingMillis] more before its limit is reached. */
    data class Allowed(val blockId: String, val remainingMillis: Long) : ForegroundAppVerdict

    /** The app must be blocked until [untilMillis]; [blockId] is the block that owns the cooldown or cap. */
    data class Restricted(val blockId: String, val untilMillis: Long, val reason: BlockReason) : ForegroundAppVerdict
}

package com.leo.blockapp.domain.model

/** An activity of an app moved to the foreground or background at [timestampMillis] (wall-clock ms). */
data class UsageEvent(
    val packageName: String,
    val activityName: String,
    val type: UsageEventType,
    val timestampMillis: Long,
)

enum class UsageEventType { MOVED_TO_FOREGROUND, MOVED_TO_BACKGROUND }

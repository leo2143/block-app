package com.leo.blockapp.domain.model

data class AppUsage(
    val app: InstalledApp,
    val foregroundMillisToday: Long,
)

sealed interface TodayUsageResult {
    /** The user has not granted Usage Access yet. */
    data object MissingUsageAccess : TodayUsageResult

    /** Apps sorted from most to least used. */
    data class Available(val appUsages: List<AppUsage>) : TodayUsageResult
}

package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.model.AppUsage
import com.leo.blockapp.domain.model.TodayUsageResult
import com.leo.blockapp.domain.ports.input.GetTodayUsage
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.ports.output.InstalledAppCatalog
import com.leo.blockapp.domain.ports.output.SystemPermissions
import com.leo.blockapp.domain.ports.output.UsageEventSource
import com.leo.blockapp.domain.usage.ForegroundTimeCalculator

/** Implements the input port using only output ports: it knows nothing about Android. */
class GetTodayUsageUseCase(
    private val usageEventSource: UsageEventSource,
    private val installedAppCatalog: InstalledAppCatalog,
    private val systemPermissions: SystemPermissions,
    private val clock: Clock,
) : GetTodayUsage {

    override suspend fun invoke(): TodayUsageResult {
        if (!systemPermissions.hasUsageAccess()) return TodayUsageResult.MissingUsageAccess

        val nowMillis = clock.nowMillis()
        val startOfTodayMillis = clock.startOfDayMillis(nowMillis)
        val events = usageEventSource.getEvents(startOfTodayMillis, nowMillis)
        val foregroundMillisByPackage =
            ForegroundTimeCalculator.calculateForegroundMillis(events, startOfTodayMillis, nowMillis)

        val appUsages = installedAppCatalog.getLaunchableApps()
            .map { app -> AppUsage(app, foregroundMillisByPackage[app.packageName] ?: 0L) }
            .sortedWith(
                compareByDescending<AppUsage> { it.foregroundMillisToday }.thenBy { it.app.label.lowercase() },
            )

        return TodayUsageResult.Available(appUsages)
    }
}

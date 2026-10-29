package com.leo.blockapp.di

import com.leo.blockapp.domain.ports.input.GetTodayUsage
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.ports.output.InstalledAppCatalog
import com.leo.blockapp.domain.ports.output.SystemPermissions
import com.leo.blockapp.domain.ports.output.UsageEventSource
import com.leo.blockapp.domain.usecases.GetTodayUsageUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds each input port to its use case. @Provides is used (instead of @Inject on the use case)
 * so the domain does not depend on any Hilt annotation.
 */
@Module
@InstallIn(SingletonComponent::class)
object UseCasesModule {

    @Provides
    fun provideGetTodayUsage(
        usageEventSource: UsageEventSource,
        installedAppCatalog: InstalledAppCatalog,
        systemPermissions: SystemPermissions,
        clock: Clock,
    ): GetTodayUsage = GetTodayUsageUseCase(usageEventSource, installedAppCatalog, systemPermissions, clock)
}

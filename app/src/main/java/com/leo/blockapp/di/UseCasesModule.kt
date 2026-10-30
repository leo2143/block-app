package com.leo.blockapp.di

import com.leo.blockapp.domain.ports.input.DeleteBlock
import com.leo.blockapp.domain.ports.input.EvaluateForegroundApp
import com.leo.blockapp.domain.ports.input.GetAppBlockingStatus
import com.leo.blockapp.domain.ports.input.GetBlock
import com.leo.blockapp.domain.ports.input.GetForegroundPackage
import com.leo.blockapp.domain.ports.input.GetTodayUsage
import com.leo.blockapp.domain.ports.input.IsAccessibilityServiceEnabled
import com.leo.blockapp.domain.ports.input.IsUsageAccessGranted
import com.leo.blockapp.domain.ports.input.ListSelectableApps
import com.leo.blockapp.domain.ports.input.ObserveBlockStatuses
import com.leo.blockapp.domain.ports.input.ObserveBlocks
import com.leo.blockapp.domain.ports.input.ObserveEnforcedPackages
import com.leo.blockapp.domain.ports.input.RestoreBlock
import com.leo.blockapp.domain.ports.input.SaveBlock
import com.leo.blockapp.domain.ports.input.SetBlockEnabled
import com.leo.blockapp.domain.ports.input.ValidateBlock
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.ports.output.IdGenerator
import com.leo.blockapp.domain.ports.output.InstalledAppCatalog
import com.leo.blockapp.domain.ports.output.SystemPermissions
import com.leo.blockapp.domain.ports.output.UsageEventSource
import com.leo.blockapp.domain.usecases.DeleteBlockUseCase
import com.leo.blockapp.domain.usecases.EvaluateForegroundAppUseCase
import com.leo.blockapp.domain.usecases.GetAppBlockingStatusUseCase
import com.leo.blockapp.domain.usecases.GetBlockUseCase
import com.leo.blockapp.domain.usecases.GetForegroundPackageUseCase
import com.leo.blockapp.domain.usecases.GetTodayUsageUseCase
import com.leo.blockapp.domain.usecases.IsAccessibilityServiceEnabledUseCase
import com.leo.blockapp.domain.usecases.IsUsageAccessGrantedUseCase
import com.leo.blockapp.domain.usecases.ListSelectableAppsUseCase
import com.leo.blockapp.domain.usecases.ObserveBlockStatusesUseCase
import com.leo.blockapp.domain.usecases.ObserveBlocksUseCase
import com.leo.blockapp.domain.usecases.ObserveEnforcedPackagesUseCase
import com.leo.blockapp.domain.usecases.RestoreBlockUseCase
import com.leo.blockapp.domain.usecases.SaveBlockUseCase
import com.leo.blockapp.domain.usecases.SetBlockEnabledUseCase
import com.leo.blockapp.domain.usecases.ValidateBlockUseCase
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

    @Provides
    fun provideObserveBlocks(blockRepository: BlockRepository): ObserveBlocks =
        ObserveBlocksUseCase(blockRepository)

    @Provides
    fun provideGetBlock(blockRepository: BlockRepository): GetBlock = GetBlockUseCase(blockRepository)

    @Provides
    fun provideValidateBlock(): ValidateBlock = ValidateBlockUseCase()

    @Provides
    fun provideSaveBlock(
        blockRepository: BlockRepository,
        idGenerator: IdGenerator,
        installedAppCatalog: InstalledAppCatalog,
        clock: Clock,
    ): SaveBlock = SaveBlockUseCase(blockRepository, idGenerator, installedAppCatalog, clock)

    @Provides
    fun provideSetBlockEnabled(blockRepository: BlockRepository): SetBlockEnabled =
        SetBlockEnabledUseCase(blockRepository)

    @Provides
    fun provideDeleteBlock(blockRepository: BlockRepository): DeleteBlock = DeleteBlockUseCase(blockRepository)

    @Provides
    fun provideRestoreBlock(
        blockRepository: BlockRepository,
        installedAppCatalog: InstalledAppCatalog,
    ): RestoreBlock = RestoreBlockUseCase(blockRepository, installedAppCatalog)

    @Provides
    fun provideListSelectableApps(
        blockRepository: BlockRepository,
        installedAppCatalog: InstalledAppCatalog,
    ): ListSelectableApps = ListSelectableAppsUseCase(blockRepository, installedAppCatalog)

    @Provides
    fun provideEvaluateForegroundApp(
        blockRepository: BlockRepository,
        blockStateRepository: BlockStateRepository,
        usageEventSource: UsageEventSource,
        clock: Clock,
    ): EvaluateForegroundApp =
        EvaluateForegroundAppUseCase(blockRepository, blockStateRepository, usageEventSource, clock)

    @Provides
    fun provideObserveEnforcedPackages(
        blockRepository: BlockRepository,
        blockStateRepository: BlockStateRepository,
        clock: Clock,
    ): ObserveEnforcedPackages = ObserveEnforcedPackagesUseCase(blockRepository, blockStateRepository, clock)

    @Provides
    fun provideGetForegroundPackage(
        usageEventSource: UsageEventSource,
        clock: Clock,
    ): GetForegroundPackage = GetForegroundPackageUseCase(usageEventSource, clock)

    @Provides
    fun provideGetAppBlockingStatus(
        blockRepository: BlockRepository,
        blockStateRepository: BlockStateRepository,
        clock: Clock,
    ): GetAppBlockingStatus = GetAppBlockingStatusUseCase(blockRepository, blockStateRepository, clock)

    @Provides
    fun provideObserveBlockStatuses(
        blockStateRepository: BlockStateRepository,
        clock: Clock,
    ): ObserveBlockStatuses = ObserveBlockStatusesUseCase(blockStateRepository, clock)

    @Provides
    fun provideIsAccessibilityServiceEnabled(systemPermissions: SystemPermissions): IsAccessibilityServiceEnabled =
        IsAccessibilityServiceEnabledUseCase(systemPermissions)

    @Provides
    fun provideIsUsageAccessGranted(systemPermissions: SystemPermissions): IsUsageAccessGranted =
        IsUsageAccessGrantedUseCase(systemPermissions)
}

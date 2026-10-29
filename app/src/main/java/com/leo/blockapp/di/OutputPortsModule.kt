package com.leo.blockapp.di

import com.leo.blockapp.adapters.output.apps.PackageManagerAppCatalog
import com.leo.blockapp.adapters.output.clock.DeviceClock
import com.leo.blockapp.adapters.output.ids.UuidIdGenerator
import com.leo.blockapp.adapters.output.permissions.AppOpsSystemPermissions
import com.leo.blockapp.adapters.output.storage.DataStoreBlockRepository
import com.leo.blockapp.adapters.output.usage.UsageStatsEventSource
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.Clock
import com.leo.blockapp.domain.ports.output.IdGenerator
import com.leo.blockapp.domain.ports.output.InstalledAppCatalog
import com.leo.blockapp.domain.ports.output.SystemPermissions
import com.leo.blockapp.domain.ports.output.UsageEventSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds each output port to the Android adapter that implements it. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OutputPortsModule {

    @Binds
    abstract fun bindUsageEventSource(implementation: UsageStatsEventSource): UsageEventSource

    @Binds
    abstract fun bindInstalledAppCatalog(implementation: PackageManagerAppCatalog): InstalledAppCatalog

    @Binds
    abstract fun bindSystemPermissions(implementation: AppOpsSystemPermissions): SystemPermissions

    @Binds
    abstract fun bindClock(implementation: DeviceClock): Clock

    @Binds
    abstract fun bindBlockRepository(implementation: DataStoreBlockRepository): BlockRepository

    @Binds
    abstract fun bindIdGenerator(implementation: UuidIdGenerator): IdGenerator
}

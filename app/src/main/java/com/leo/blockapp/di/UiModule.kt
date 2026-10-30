package com.leo.blockapp.di

import android.os.SystemClock
import com.leo.blockapp.adapters.input.ui.blocking.MonotonicClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Dependencies of the UI adapters that are not domain ports. */
@Module
@InstallIn(SingletonComponent::class)
object UiModule {

    /** Counts time since boot including deep sleep and is unaffected by wall-clock changes. */
    @Provides
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }
}

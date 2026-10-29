package com.leo.blockapp.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.leo.blockapp.adapters.output.storage.BlocksDataStoreFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Provides the process-wide blocks DataStore. It must exist exactly once: two DataStore instances on the same
 * file make DataStore throw, hence @Singleton.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    private const val BLOCKS_DATA_STORE_NAME = "blocks"

    @Provides
    @Singleton
    fun provideBlocksDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        BlocksDataStoreFactory.create(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            context.preferencesDataStoreFile(BLOCKS_DATA_STORE_NAME)
        }
}

package com.leo.blockapp.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.leo.blockapp.adapters.output.storage.BlocksDataStoreFactory
import com.leo.blockapp.adapters.output.storage.DataStoreBlockStateRepository
import com.leo.blockapp.domain.ports.output.BlockStateRepository
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
 * Provides the block state repository over its own `block_state` DataStore. The DataStore is built inside the
 * provider, so no second unqualified `DataStore<Preferences>` binding exists next to the blocks one in
 * [StorageModule]. It must exist exactly once per file, hence @Singleton.
 */
@Module
@InstallIn(SingletonComponent::class)
object BlockStateStorageModule {

    private const val BLOCK_STATE_DATA_STORE_NAME = "block_state"

    @Provides
    @Singleton
    fun provideBlockStateRepository(@ApplicationContext context: Context): BlockStateRepository =
        DataStoreBlockStateRepository(
            BlocksDataStoreFactory.create(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                context.preferencesDataStoreFile(BLOCK_STATE_DATA_STORE_NAME)
            },
        )
}

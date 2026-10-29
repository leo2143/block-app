package com.leo.blockapp.adapters.output.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.leo.blockapp.domain.model.Block
import com.leo.blockapp.domain.ports.output.BlockChanges
import com.leo.blockapp.domain.ports.output.BlockRepository
import com.leo.blockapp.domain.ports.output.BlockRepositoryContract
import com.leo.blockapp.domain.ports.output.BlockUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Runs the shared repository contract against the real DataStore adapter (real file, real IO scope), plus the
 * adapter-specific storage behavior: persistence across restarts, corruption handling and read/write failures.
 */
class DataStoreBlockRepositoryTest : BlockRepositoryContract() {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun cancelIoScope() {
        ioScope.cancel()
    }

    /** DataStore frees its file only once the scope completes; join so a restart can reopen the same file. */
    private fun stopAndJoin(scope: CoroutineScope) = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    // The file must not pre-exist: DataStore creates it on the first write.
    private fun blocksFile() = File(temporaryFolder.root, "blocks.preferences_pb")

    private fun newRepository(file: File = blocksFile(), scope: CoroutineScope = ioScope) =
        DataStoreBlockRepository(BlocksDataStoreFactory.create(scope) { file })

    override fun createRepository(): BlockRepository = newRepository()

    @Test
    fun `the blocks file does not exist before the first write`() = runTest {
        val repository = newRepository()

        assertEquals(emptyList<Block>(), repository.getBlocks())
        assertFalse(blocksFile().exists())
    }

    @Test
    fun `observeBlocks emits the initial list and every change`() = runTest {
        val repository = newRepository()

        val emissions = async(Dispatchers.IO) { repository.observeBlocks().take(3).toList() }
        repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B1", "a"))), Unit) }
        repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B2", "b"))), Unit) }

        val seen = emissions.await()
        assertEquals(
            listOf(emptyList(), listOf("B1"), listOf("B1", "B2")),
            seen.map { blocks -> blocks.map { it.id } },
        )
    }

    @Test
    fun `blocks survive a restart of the data store`() = runTest {
        val file = blocksFile()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = newRepository(file, firstScope)
        first.update {
            BlockUpdate(BlockChanges(upserts = listOf(block("B1", "z", "a"), block("B2", "m", position = 5))), Unit)
        }
        stopAndJoin(firstScope)

        val restarted = newRepository(file)

        assertEquals(listOf(block("B1", "z", "a"), block("B2", "m", position = 5)), restarted.getBlocks())
        assertEquals(listOf("z", "a"), restarted.getBlocks().first().packageNames.toList())
    }

    @Test
    fun `a corrupt file is replaced by an empty list and the repository keeps working`() = runTest {
        val file = blocksFile()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 99, 100))
        val repository = newRepository(file)

        assertEquals(emptyList<Block>(), repository.getBlocks())

        repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B1", "a"))), Unit) }
        assertEquals(listOf(block("B1", "a")), repository.getBlocks())
    }

    @Test
    fun `undecodable json stored under the key reads as an empty list`() = runTest {
        val file = blocksFile()
        val seedingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val seeding = BlocksDataStoreFactory.create(seedingScope) { file }
        seeding.updateData { preferences ->
            preferences.toMutablePreferences().apply { this[DataStoreBlockRepository.BLOCKS_KEY] = "{not json" }
        }
        stopAndJoin(seedingScope)

        assertEquals(emptyList<Block>(), newRepository(file).getBlocks())
    }

    @Test
    fun `an IOException while reading emits an empty list`() = runTest {
        val repository = DataStoreBlockRepository(FakePreferencesDataStore(readFailure = IOException("read failed")))

        assertEquals(emptyList<Block>(), repository.getBlocks())
        assertEquals(emptyList<Block>(), repository.observeBlocks().first())
    }

    @Test
    fun `a non IOException while reading is rethrown`() = runTest {
        val repository = DataStoreBlockRepository(FakePreferencesDataStore(readFailure = IllegalStateException("boom")))

        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { repository.getBlocks() } }

        assertEquals("boom", thrown.message)
    }

    @Test
    fun `an IOException while writing propagates to the caller`() = runTest {
        val repository = DataStoreBlockRepository(FakePreferencesDataStore(writeFailure = IOException("disk full")))

        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                repository.update { BlockUpdate(BlockChanges(upserts = listOf(block("B1", "a"))), Unit) }
            }
        }

        assertEquals("disk full", thrown.message)
    }

    @Test
    fun `concurrent updates on separate threads never lose a write`() = runBlocking {
        val repository = newRepository()

        List(50) { index ->
            async(Dispatchers.IO) {
                repository.update { current ->
                    val position = index.toLong()
                    BlockUpdate(
                        BlockChanges(
                            upserts = listOf(block("B$index", "p$index", position = position)),
                            insertAt = mapOf("B$index" to current.count { it.position < position }),
                        ),
                        Unit,
                    )
                }
            }
        }.awaitAll()

        assertEquals((0 until 50).map { "B$it" }, repository.getBlocks().map { it.id })
    }

    /** A [DataStore] stub whose reads and/or writes fail, to exercise the adapter's error handling. */
    private class FakePreferencesDataStore(
        private val readFailure: Throwable? = null,
        private val writeFailure: Throwable? = null,
    ) : DataStore<Preferences> {

        override val data: Flow<Preferences> =
            if (readFailure != null) flow { throw readFailure } else flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            writeFailure?.let { throw it }
            return transform(emptyPreferences())
        }
    }
}

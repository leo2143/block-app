package com.leo.blockapp.adapters.output.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockState
import com.leo.blockapp.domain.ports.output.BlockStateRepository
import com.leo.blockapp.domain.ports.output.BlockStateRepositoryContract
import com.leo.blockapp.domain.ports.output.BlockStateUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
 * Runs the shared state repository contract against the real DataStore adapter (real file, real IO scope), plus the
 * adapter-specific storage behavior: persistence across restarts (S6), corruption handling and read/write failures.
 */
class DataStoreBlockStateRepositoryTest : BlockStateRepositoryContract() {

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
    private fun statesFile() = File(temporaryFolder.root, "block_state.preferences_pb")

    private fun newRepository(file: File = statesFile(), scope: CoroutineScope = ioScope) =
        DataStoreBlockStateRepository(BlocksDataStoreFactory.create(scope) { file })

    override fun createRepository(): BlockStateRepository = newRepository()

    @Test
    fun `the states file does not exist before the first write`() = runTest {
        val repository = newRepository()

        assertEquals(emptyMap<String, BlockState>(), repository.observeStates().first())
        assertFalse(statesFile().exists())
    }

    @Test
    fun `an empty update does not create the file`() = runTest {
        val repository = newRepository()

        val result = repository.update { current -> BlockStateUpdate(result = current.size) }

        assertEquals(0, result)
        assertFalse(statesFile().exists())
    }

    @Test
    fun `observeStates emits the initial map and every change`() = runTest {
        val repository = newRepository()

        val received = Channel<Set<String>>(Channel.UNLIMITED)
        val collector = ioScope.launch {
            repository.observeStates().collect { states -> received.send(states.keys) }
        }

        // Each update waits for the previous state, so subscription timing and conflation cannot affect the result.
        received.awaitState(emptySet())
        repository.update { BlockStateUpdate(upserts = mapOf("B1" to state()), result = Unit) }
        received.awaitState(setOf("B1"))
        repository.update { BlockStateUpdate(upserts = mapOf("B2" to state()), result = Unit) }
        received.awaitState(setOf("B1", "B2"))

        collector.cancel()
    }

    @Test
    fun `observeStates keeps emitting after a read IOException once the store recovers`() = runTest {
        val stored = BlockStateJsonMapper.encode(mapOf("B1" to state("One")))
        val dataStore = RecoveringDataStore(stored, failures = 1)
        val repository = DataStoreBlockStateRepository(dataStore)

        val emissions = repository.observeStates().take(2).toList()

        assertEquals(listOf(emptyMap(), mapOf("B1" to state("One"))), emissions)
        assertEquals(2, dataStore.collections)
    }

    @Test
    fun `S6 a running cooldown and hold survive a restart of the data store`() = runTest {
        val file = statesFile()
        val running = state(
            blockName = "Social",
            cyclesToday = 3,
            cooldownEndMillis = 9_000L,
            holdUntilMillis = 86_400_000L,
            holdReason = BlockReason.DAILY_CAP,
            holdPackageNames = linkedSetOf("z", "a"),
        )
        val idle = state(blockName = "Idle")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        newRepository(file, firstScope).update {
            BlockStateUpdate(upserts = mapOf("B1" to running, "B2" to idle), result = Unit)
        }
        stopAndJoin(firstScope)

        val restarted = newRepository(file).observeStates().first()

        assertEquals(mapOf("B1" to running, "B2" to idle), restarted)
        assertEquals(null, restarted.getValue("B2").cooldownEndMillis)
        assertEquals(null, restarted.getValue("B2").holdUntilMillis)
        assertEquals(listOf("z", "a"), restarted.getValue("B1").holdPackageNames.toList())
    }

    @Test
    fun `a corrupt file is replaced by an empty map and the repository keeps working`() = runTest {
        val file = statesFile()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 99, 100))
        val repository = newRepository(file)

        assertEquals(emptyMap<String, BlockState>(), repository.observeStates().first())

        repository.update { BlockStateUpdate(upserts = mapOf("B1" to state("One")), result = Unit) }
        assertEquals(mapOf("B1" to state("One")), repository.observeStates().first())
    }

    @Test
    fun `undecodable json stored under the key reads as an empty map`() = runTest {
        val file = statesFile()
        val seedingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val seeding = BlocksDataStoreFactory.create(seedingScope) { file }
        seeding.updateData { preferences ->
            preferences.toMutablePreferences().apply { this[DataStoreBlockStateRepository.STATES_KEY] = "{not json" }
        }
        stopAndJoin(seedingScope)

        assertEquals(emptyMap<String, BlockState>(), newRepository(file).observeStates().first())
    }

    @Test
    fun `an unsupported schema version stored under the key reads as an empty map`() = runTest {
        val file = statesFile()
        val seedingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val newer = BlockStateJsonMapper.encode(mapOf("B1" to state("One"))).replace("\"schemaVersion\":1", "\"schemaVersion\":9")
        BlocksDataStoreFactory.create(seedingScope) { file }.updateData { preferences ->
            preferences.toMutablePreferences().apply { this[DataStoreBlockStateRepository.STATES_KEY] = newer }
        }
        stopAndJoin(seedingScope)

        assertEquals(emptyMap<String, BlockState>(), newRepository(file).observeStates().first())
    }

    @Test
    fun `an IOException while reading emits an empty map`() = runTest {
        val repository = DataStoreBlockStateRepository(FakePreferencesDataStore(readFailure = IOException("read failed")))

        assertEquals(emptyMap<String, BlockState>(), repository.observeStates().first())
    }

    @Test
    fun `a non IOException while reading is rethrown`() = runTest {
        val repository = DataStoreBlockStateRepository(FakePreferencesDataStore(readFailure = IllegalStateException("boom")))

        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { repository.observeStates().first() } }

        assertEquals("boom", thrown.message)
    }

    @Test
    fun `an IOException while writing propagates to the caller`() = runTest {
        val repository = DataStoreBlockStateRepository(FakePreferencesDataStore(writeFailure = IOException("disk full")))

        val thrown = assertThrows(IOException::class.java) {
            runBlocking {
                repository.update { BlockStateUpdate(upserts = mapOf("B1" to state()), result = Unit) }
            }
        }

        assertEquals("disk full", thrown.message)
    }

    @Test
    fun `concurrent updates on separate threads never lose a write`() = runBlocking {
        val repository = newRepository()

        List(50) { index ->
            async(Dispatchers.IO) {
                repository.update { BlockStateUpdate(upserts = mapOf("B$index" to state()), result = Unit) }
            }
        }.awaitAll()

        assertEquals((0 until 50).map { "B$it" }.toSet(), repository.observeStates().first().keys)
    }

    @Test
    fun `concurrent read-modify-write updates on separate threads never lose an increment`() = runBlocking {
        val repository = newRepository()
        repository.update { BlockStateUpdate(upserts = mapOf("B1" to state()), result = Unit) }

        List(50) {
            async(Dispatchers.IO) {
                repository.update { current ->
                    val counter = checkNotNull(current["B1"])
                    BlockStateUpdate(upserts = mapOf("B1" to counter.copy(cyclesToday = counter.cyclesToday + 1)), result = Unit)
                }
            }
        }.awaitAll()

        assertEquals(50, repository.observeStates().first().getValue("B1").cyclesToday)
    }

    private suspend fun Channel<Set<String>>.awaitState(expected: Set<String>) {
        while (receive() != expected) {
            // Skip states that were observed before the expected one.
        }
    }

    /** A [DataStore] whose data flow throws an IOException for the first [failures] collections, then serves [stored]. */
    private class RecoveringDataStore(stored: String, private val failures: Int) : DataStore<Preferences> {

        var collections = 0
            private set

        private val recovered = emptyPreferences().toMutablePreferences().apply {
            this[DataStoreBlockStateRepository.STATES_KEY] = stored
        }

        override val data: Flow<Preferences> = flow {
            collections++
            if (collections <= failures) throw IOException("read failed")
            emit(recovered)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(recovered)
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

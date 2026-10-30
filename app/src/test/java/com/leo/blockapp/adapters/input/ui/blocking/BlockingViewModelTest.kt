package com.leo.blockapp.adapters.input.ui.blocking

import com.leo.blockapp.adapters.input.ui.MainDispatcherRule
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockScreenState
import com.leo.blockapp.domain.ports.input.GetAppBlockingStatus
import kotlinx.coroutines.CompletableDeferred
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BlockingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val second = 1_000L
    private val minute = 60 * second
    private val instagram = "com.instagram.android"
    private val x = "com.twitter.android"

    private val statuses = mutableListOf<ScriptedStatus>()
    private val manualClocks = mutableListOf<ManualMonotonicClock>()
    private val gates = mutableListOf<CompletableDeferred<BlockScreenState>>()
    private val viewModels = mutableListOf<BlockingViewModel>()

    private fun newGate() = CompletableDeferred<BlockScreenState>().also { gates += it }

    /**
     * runTest drains the scheduler after the body, and a countdown that re-queries forever never goes idle. So when
     * the body ends everything is released: statuses answer Inactive, manual clocks jump ahead, gates open.
     */
    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.dispatcher, timeout = 10.seconds) {
        try {
            body()
        } finally {
            // Stop every ViewModel first so a regression in the ticker or retry loop fails the test instead of draining forever.
            viewModels.forEach { it.viewModelScope.cancel() }
            statuses.forEach { it.answer = { BlockScreenState.Inactive } }
            manualClocks.forEach { it.nowMillis = Long.MAX_VALUE / 2 }
            gates.forEach { it.complete(BlockScreenState.Inactive) }
        }
    }

    private inner class ScriptedStatus(var answer: suspend (String) -> BlockScreenState) : GetAppBlockingStatus {
        init {
            statuses += this
        }

        val queries = mutableListOf<String>()

        override suspend fun invoke(packageName: String): BlockScreenState {
            queries += packageName
            return answer(packageName)
        }
    }

    /** A monotonic clock the test moves by hand, independent of the coroutine scheduler. */
    private inner class ManualMonotonicClock(var nowMillis: Long = 0L) : MonotonicClock {
        init {
            manualClocks += this
        }

        override fun elapsedMillis() = nowMillis
    }

    private fun active(remaining: Long, name: String = "Redes", reason: BlockReason = BlockReason.COOLDOWN) =
        BlockScreenState.Active(name, untilMillis = 1_000_000L + remaining, remainingMillis = remaining, reason = reason)

    private fun TestScope.viewModel(
        status: ScriptedStatus,
        clock: MonotonicClock = MonotonicClock { testScheduler.currentTime },
    ) = BlockingViewModel(status, clock).also { viewModels += it }

    private fun blocked(remaining: Long, name: String = "Redes", reason: BlockReason = BlockReason.COOLDOWN) =
        BlockingUiState.Blocked(name, reason, remaining)

    @Test
    fun `it starts loading and shows the status once queried`() = vmTest {
        val vm = viewModel(ScriptedStatus { active(35 * minute) })

        assertEquals(BlockingUiState.Loading, vm.uiState.value)

        vm.show(instagram)
        runCurrent()

        assertEquals(blocked(35 * minute), vm.uiState.value)
    }

    @Test
    fun `an inactive status finishes the screen`() = vmTest {
        val vm = viewModel(ScriptedStatus { BlockScreenState.Inactive })

        vm.show(instagram)
        runCurrent()

        assertEquals(BlockingUiState.Finished, vm.uiState.value)
    }

    @Test
    fun `the countdown follows the monotonic clock every second`() = vmTest {
        val vm = viewModel(ScriptedStatus { active(10 * second) })
        vm.show(instagram)
        runCurrent()

        advanceTimeBy(3 * second)
        runCurrent()

        assertEquals(blocked(7 * second), vm.uiState.value)
    }

    @Test
    fun `a late tick does not shift the countdown because it is measured and not accumulated`() =
        vmTest {
            val clock = ManualMonotonicClock()
            val vm = viewModel(ScriptedStatus { active(60 * second) }, clock)
            vm.show(instagram)
            runCurrent()

            // The device slept for 20 s: the ticker fires once, but the monotonic clock moved 20 s.
            clock.nowMillis = 20 * second
            advanceTimeBy(second)
            runCurrent()

            assertEquals(blocked(40 * second), vm.uiState.value)
        }

    @Test
    fun `the countdown only moves with the monotonic clock, not with elapsed ticks`() =
        vmTest {
            val clock = ManualMonotonicClock()
            val vm = viewModel(ScriptedStatus { active(60 * second) }, clock)
            vm.show(instagram)
            runCurrent()

            advanceTimeBy(5 * second)
            runCurrent()

            // The monotonic clock did not move, so neither does the remaining time.
            assertEquals(blocked(60 * second), vm.uiState.value)
        }

    @Test
    fun `it re-queries when the countdown reaches zero and finishes when nothing holds the app`() =
        vmTest {
            val status = ScriptedStatus { active(3 * second) }
            val vm = viewModel(status)
            vm.show(instagram)
            runCurrent()
            status.answer = { BlockScreenState.Inactive }

            advanceTimeBy(3 * second)
            runCurrent()

            assertEquals(listOf(instagram, instagram), status.queries)
            assertEquals(BlockingUiState.Finished, vm.uiState.value)
        }

    @Test
    fun `at zero a hold that is still active keeps the screen with the new remaining time`() =
        vmTest {
            val status = ScriptedStatus { active(2 * second) }
            val vm = viewModel(status)
            vm.show(instagram)
            runCurrent()
            status.answer = { active(30 * second, reason = BlockReason.DAILY_CAP) }

            advanceTimeBy(2 * second)
            runCurrent()

            assertEquals(blocked(30 * second, reason = BlockReason.DAILY_CAP), vm.uiState.value)
        }

    @Test
    fun `it never counts below zero and fails closed when the re-query at zero fails`() = vmTest {
        val clock = ManualMonotonicClock()
        val status = ScriptedStatus { active(5 * second) }
        val vm = viewModel(status, clock)
        vm.show(instagram)
        runCurrent()

        clock.nowMillis = 60 * second
        status.answer = { throw IOException("disk") }
        advanceTimeBy(second)
        runCurrent()

        assertEquals(BlockingUiState.Unavailable, vm.uiState.value)
    }

    @Test
    fun `a failed re-query at zero shows unavailable and a later answer shows the new state`() = vmTest {
        val status = ScriptedStatus { active(3 * second) }
        val vm = viewModel(status)
        vm.show(instagram)
        runCurrent()
        status.answer = { throw IOException("disk") }

        advanceTimeBy(3 * second)
        runCurrent()
        assertEquals(BlockingUiState.Unavailable, vm.uiState.value)

        status.answer = { active(5 * minute) }
        advanceTimeBy(second)
        runCurrent()

        assertEquals(blocked(5 * minute), vm.uiState.value)
    }

    @Test
    fun `stopping cancels the ticker so nothing is queried or emitted while stopped`() = vmTest {
        val status = ScriptedStatus { active(10 * second) }
        val vm = viewModel(status)
        vm.show(instagram)
        runCurrent()

        vm.onStop()
        val queriesWhenStopped = status.queries.size
        advanceTimeBy(30 * second)
        runCurrent()

        assertEquals(blocked(10 * second), vm.uiState.value)
        assertEquals(queriesWhenStopped, status.queries.size)
    }

    @Test
    fun `stopping cancels the retry loop while storage keeps failing`() = vmTest {
        val status = ScriptedStatus { throw IOException("disk") }
        val vm = viewModel(status)
        vm.show(instagram)
        runCurrent()

        vm.onStop()
        val queriesWhenStopped = status.queries.size
        advanceTimeBy(10 * second)
        runCurrent()

        assertEquals(queriesWhenStopped, status.queries.size)
    }

    @Test
    fun `resuming after a stop re-queries and resumes the countdown`() = vmTest {
        val status = ScriptedStatus { active(10 * second) }
        val vm = viewModel(status)
        vm.show(instagram)
        runCurrent()
        vm.onStop()
        status.answer = { active(5 * second) }

        vm.onResume()
        runCurrent()
        assertEquals(blocked(5 * second), vm.uiState.value)

        advanceTimeBy(2 * second)
        runCurrent()

        assertEquals(blocked(3 * second), vm.uiState.value)
        assertEquals(2, status.queries.size)
    }

    @Test
    fun `resuming re-queries the status and recomputes the countdown`() = vmTest {
        val status = ScriptedStatus { active(10 * minute) }
        val vm = viewModel(status)
        vm.show(instagram)
        runCurrent()
        status.answer = { active(4 * minute) }

        vm.onResume()
        runCurrent()

        assertEquals(blocked(4 * minute), vm.uiState.value)
        assertEquals(2, status.queries.size)
    }

    @Test
    fun `resuming after the hold ended finishes the screen`() = vmTest {
        val status = ScriptedStatus { active(10 * minute) }
        val vm = viewModel(status)
        vm.show(instagram)
        runCurrent()
        status.answer = { BlockScreenState.Inactive }

        vm.onResume()
        runCurrent()

        assertEquals(BlockingUiState.Finished, vm.uiState.value)
    }

    @Test
    fun `resuming before a package was shown does nothing`() = vmTest {
        val status = ScriptedStatus { active(minute) }
        val vm = viewModel(status)

        vm.onResume()
        runCurrent()

        assertEquals(emptyList<String>(), status.queries)
        assertEquals(BlockingUiState.Loading, vm.uiState.value)
    }

    @Test
    fun `showing another package re-keys the screen to the new data`() = vmTest {
        val status = ScriptedStatus { pkg ->
            if (pkg == instagram) active(10 * minute, name = "Redes") else active(20 * minute, name = "Juegos")
        }
        val vm = viewModel(status)
        vm.show(instagram)
        runCurrent()

        vm.show(x)
        runCurrent()

        assertEquals(blocked(20 * minute, name = "Juegos"), vm.uiState.value)
        assertEquals(listOf(instagram, x), status.queries)
    }

    @Test
    fun `re-keying clears the previous package data while the new one loads`() = vmTest {
        val gate = newGate()
        val status = ScriptedStatus { pkg -> if (pkg == instagram) active(10 * minute, name = "Redes") else gate.await() }
        val vm = viewModel(status)
        vm.show(instagram)
        runCurrent()

        vm.show(x)
        runCurrent()

        assertEquals(BlockingUiState.Loading, vm.uiState.value)
    }

    @Test
    fun `a slow answer for the previous package never overwrites the new package`() =
        vmTest {
            val slow = newGate()
            val status = ScriptedStatus { pkg -> if (pkg == instagram) slow.await() else active(20 * minute, name = "Juegos") }
            val vm = viewModel(status)
            vm.show(instagram)
            runCurrent()
            vm.show(x)
            runCurrent()

            slow.complete(active(10 * minute, name = "Redes"))
            runCurrent()

            assertEquals(blocked(20 * minute, name = "Juegos"), vm.uiState.value)
        }

    @Test
    fun `showing the same package again re-queries but keeps what is on screen`() =
        vmTest {
            val status = ScriptedStatus { active(10 * minute) }
            val vm = viewModel(status)
            vm.show(instagram)
            runCurrent()

            vm.show(instagram)

            assertEquals(blocked(10 * minute), vm.uiState.value)
            runCurrent()
            assertEquals(2, status.queries.size)
        }

    @Test
    fun `a storage error on the first query keeps the app blocked and never finishes`() =
        vmTest {
            val vm = viewModel(ScriptedStatus { throw IOException("disk") })

            vm.show(instagram)
            runCurrent()

            assertEquals(BlockingUiState.Unavailable, vm.uiState.value)
        }

    @Test
    fun `after a storage error it retries and recovers`() = vmTest {
        val status = ScriptedStatus { throw IOException("disk") }
        val vm = viewModel(status)
        vm.show(instagram)
        runCurrent()
        status.answer = { active(5 * minute) }

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(blocked(5 * minute), vm.uiState.value)
    }

    @Test
    fun `a failed re-query after a stop shows unavailable instead of a frozen countdown and later recovers`() =
        vmTest {
            val status = ScriptedStatus { active(10 * minute) }
            val vm = viewModel(status)
            vm.show(instagram)
            runCurrent()
            vm.onStop()
            status.answer = { throw IOException("disk") }

            vm.onResume()
            runCurrent()

            assertEquals(BlockingUiState.Unavailable, vm.uiState.value)

            status.answer = { active(3 * minute) }
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(blocked(3 * minute), vm.uiState.value)
        }

    @Test
    fun `a failed re-query of the same package shows unavailable and never finishes`() =
        vmTest {
            val status = ScriptedStatus { active(10 * minute) }
            val vm = viewModel(status)
            vm.show(instagram)
            runCurrent()
            status.answer = { throw IOException("disk") }

            vm.show(instagram)
            runCurrent()

            assertEquals(BlockingUiState.Unavailable, vm.uiState.value)
        }

    @Test
    fun `it keeps retrying while storage keeps failing and only finishes on a real inactive answer`() =
        vmTest {
            val status = ScriptedStatus { throw IOException("disk") }
            val vm = viewModel(status)
            vm.show(instagram)
            runCurrent()

            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(BlockingUiState.Unavailable, vm.uiState.value)

            status.answer = { BlockScreenState.Inactive }
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(BlockingUiState.Finished, vm.uiState.value)
        }

    @Test
    fun `an unexpected runtime error also fails closed`() = vmTest {
        val vm = viewModel(ScriptedStatus { throw IllegalStateException("boom") })

        vm.show(instagram)
        runCurrent()

        assertEquals(BlockingUiState.Unavailable, vm.uiState.value)
    }
}

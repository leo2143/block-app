package com.leo.blockapp.adapters.input.ui.blocking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.BlockScreenState
import com.leo.blockapp.domain.ports.input.GetAppBlockingStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Time source that only moves forward and ignores wall-clock changes (the UI has no domain Clock). */
fun interface MonotonicClock {
    fun elapsedMillis(): Long
}

sealed interface BlockingUiState {
    /** Nothing known yet. The screen is up, so the app stays covered. */
    data object Loading : BlockingUiState

    /** The app is held; [remainingMillis] already accounts for the time elapsed since the query. */
    data class Blocked(val blockName: String, val reason: BlockReason, val remainingMillis: Long) : BlockingUiState

    /** The status could not be read. Fail closed: the app stays blocked with a generic message while we retry. */
    data object Unavailable : BlockingUiState

    /** Nothing holds the app any more: the activity can finish. */
    data object Finished : BlockingUiState
}

/**
 * Input adapter: only knows the GetAppBlockingStatus port. The countdown starts from the `remainingMillis` of the
 * last query and is measured with a [MonotonicClock], so a late tick or a system time change cannot shift it. The
 * status is re-queried when the screen resumes and when the countdown reaches zero.
 *
 * A failed query is never treated as "released": the screen keeps its last known content while it is still counting
 * down, switches to a generic blocked state once that content is stale (nothing known, or the countdown hit zero),
 * and retries until the port gives a real answer. The job only runs while the screen is visible ([onStop]).
 */
@HiltViewModel
class BlockingViewModel @Inject constructor(
    private val getAppBlockingStatus: GetAppBlockingStatus,
    private val monotonicClock: MonotonicClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BlockingUiState>(BlockingUiState.Loading)
    val uiState: StateFlow<BlockingUiState> = _uiState.asStateFlow()

    private var packageName: String? = null
    private var job: Job? = null

    /** True only while the current job is ticking the Blocked content on screen; a stopped or restarted job leaves it stale. */
    private var countdownRunning = false

    /**
     * Points the screen at [packageName]. Data of a previously shown package is dropped; showing the same package
     * again (rotation, relaunch) just re-queries and keeps what is on screen.
     */
    fun show(packageName: String) {
        if (this.packageName != packageName || _uiState.value == BlockingUiState.Finished) {
            _uiState.value = BlockingUiState.Loading
        }
        this.packageName = packageName
        start(packageName)
    }

    fun onResume() {
        packageName?.let(::start)
    }

    /** The screen is no longer visible: stop ticking and retrying. [onResume] picks the status up again. */
    fun onStop() {
        job?.cancel()
        job = null
        countdownRunning = false
    }

    private fun start(packageName: String) {
        job?.cancel()
        countdownRunning = false
        job = viewModelScope.launch { follow(packageName) }
    }

    private suspend fun follow(packageName: String) {
        while (true) {
            when (val status = query(packageName)) {
                null -> {
                    if (isContentUntrusted(_uiState.value)) _uiState.value = BlockingUiState.Unavailable
                    delay(RETRY_DELAY_MILLIS)
                }
                BlockScreenState.Inactive -> {
                    _uiState.value = BlockingUiState.Finished
                    return
                }
                is BlockScreenState.Active -> countDown(status)
            }
        }
    }

    /**
     * Nothing known yet, a countdown that already hit zero, or content no running countdown backs any more (stopped,
     * or restarted by a re-query that failed): do not keep it on screen.
     */
    private fun isContentUntrusted(state: BlockingUiState): Boolean =
        state == BlockingUiState.Loading ||
            (state is BlockingUiState.Blocked && (state.remainingMillis == 0L || !countdownRunning))

    /** The status, or null when it could not be read. Cancellation is never swallowed. */
    private suspend fun query(packageName: String): BlockScreenState? = try {
        getAppBlockingStatus(packageName)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        null
    }

    private suspend fun countDown(status: BlockScreenState.Active) {
        val startedAt = monotonicClock.elapsedMillis()
        countdownRunning = true
        while (true) {
            val remaining = (status.remainingMillis - (monotonicClock.elapsedMillis() - startedAt)).coerceAtLeast(0)
            _uiState.value = BlockingUiState.Blocked(status.blockName, status.reason, remaining)
            if (remaining == 0L) return
            delay(minOf(TICK_MILLIS, remaining))
        }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val RETRY_DELAY_MILLIS = 1_000L
    }
}

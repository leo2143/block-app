package com.leo.blockapp.adapters.input.service.enforcer

import com.leo.blockapp.domain.model.ForegroundApp
import com.leo.blockapp.domain.model.ForegroundAppVerdict
import com.leo.blockapp.domain.ports.input.EvaluateForegroundApp
import com.leo.blockapp.domain.ports.input.GetForegroundPackage
import com.leo.blockapp.domain.ports.input.ObserveEnforcedPackages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

/** Records every evaluation and answers from [verdictFor] unless [failure] is set. */
class FakeEvaluateForegroundApp : EvaluateForegroundApp {
    data class Call(val packageName: String, val foregroundSinceMillis: Long)

    val calls = mutableListOf<Call>()
    var verdictFor: (String) -> ForegroundAppVerdict = { ForegroundAppVerdict.Allowed("B1", 60_000) }
    var failure: Throwable? = null

    override suspend fun invoke(packageName: String, foregroundSinceMillis: Long): ForegroundAppVerdict {
        calls += Call(packageName, foregroundSinceMillis)
        failure?.let { throw it }
        return verdictFor(packageName)
    }
}

class FakeGetForegroundPackage : GetForegroundPackage {
    var app: ForegroundApp? = null
    var failure: Throwable? = null
    var queries = 0

    /** Runs once after the snapshot is taken, so a test can let the world change while the read is pending. */
    var onNextQuery: (suspend () -> Unit)? = null

    override suspend fun invoke(): ForegroundApp? {
        queries++
        failure?.let { throw it }
        val snapshot = app
        onNextQuery?.let {
            onNextQuery = null
            it()
        }
        return snapshot
    }
}

/** The enforced set is pushed through [emissions]; the first [failuresBeforeSuccess] collections throw instead. */
class FakeObserveEnforcedPackages : ObserveEnforcedPackages {
    val emissions = MutableSharedFlow<Set<String>>(replay = 1)
    var failuresBeforeSuccess = 0
    var collections = 0

    override fun invoke(): Flow<Set<String>> = flow {
        collections++
        if (collections <= failuresBeforeSuccess) throw java.io.IOException("storage unreadable")
        emissions.collect { emit(it) }
    }
}

class RecordingBlockScreenLauncher : BlockScreenLauncher {
    val launches = mutableListOf<String>()
    var homeCount = 0
    var launchFailure: Throwable? = null

    override fun showBlockScreen(packageName: String) {
        launches += packageName
        launchFailure?.let { throw it }
    }

    override fun goHome() {
        homeCount++
    }
}

class FakeWindowVisibility : WindowVisibility {
    val visiblePackages = mutableSetOf<String>()
    var failure: Throwable? = null

    override fun isPackageVisible(packageName: String): Boolean {
        failure?.let { throw it }
        return packageName in visiblePackages
    }
}

class FakeScreenState(var interactiveAndUnlocked: Boolean = true) : ScreenState {
    override fun isInteractiveAndUnlocked(): Boolean = interactiveAndUnlocked
}

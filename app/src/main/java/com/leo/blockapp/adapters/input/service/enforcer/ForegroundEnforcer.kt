package com.leo.blockapp.adapters.input.service.enforcer

import com.leo.blockapp.domain.model.ForegroundApp
import com.leo.blockapp.domain.model.ForegroundAppVerdict
import com.leo.blockapp.domain.ports.input.EvaluateForegroundApp
import com.leo.blockapp.domain.ports.input.GetForegroundPackage
import com.leo.blockapp.domain.ports.input.ObserveEnforcedPackages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Decides when to evaluate the foreground app and when to show the block screen. It is plain Kotlin: it only sees
 * the input ports and `domain.model`, plus the small interfaces of this package, so the whole policy runs on the
 * JVM. The accessibility service just feeds it window events.
 *
 * How it works:
 * - A window event of an enforced app starts a loop: evaluate at once, then every [EnforcerTiming.tickMillis].
 *   Detection therefore lags up to about 5 s, which the spec accepts.
 * - Every tick after the first confirms the real foreground through [GetForegroundPackage] before evaluating. The
 *   loop ends only when that confirms HOME, another app that is not enforced, or this app's own window (the block
 *   screen). Overlays (system UI, keyboards, chooser, apps without a launcher icon) never end it.
 * - On [ForegroundAppVerdict.Restricted] the block screen is launched, and if no window of this app shows up within
 *   [EnforcerTiming.launchConfirmMillis] the user is sent home instead. While the app stays in front and the launch
 *   is unconfirmed, each tick launches it again.
 * - Nothing is evaluated or launched while the screen is off or the keyguard is showing; the loop ends and
 *   [onDeviceAwake] (or the next window event) re-arms it.
 * - Errors never escape: they are logged. If an evaluation fails and the last verdict for the app was a restriction
 *   that has not ended yet, the block screen is shown anyway (fail closed); otherwise the tick is skipped.
 * - Until the enforced set has emitted once (it cannot while storage is unreadable) every launchable app is
 *   evaluated, because "no emission yet" does not mean "nothing to enforce".
 *
 * - Usage reports only the latest app, so in split screen or freeform the target can be on screen while another app
 *   is reported. Before the loop ends on such a reading, [WindowVisibility] is asked and a visible target keeps being
 *   enforced.
 * - A transient window (keyboard, system UI) while no loop runs triggers one coalesced re-evaluation, so a window
 *   event of an app that was swallowed by the overlay is recovered. A foreground snapshot that was read before a loop
 *   started or stopped is discarded, and the home fallback re-reads the foreground before acting.
 * - Fail-closed memory is in process only: the last known restrictions are lost when the service restarts, so spec
 *   F4 fail-closed is guaranteed only for restrictions seen in the current process. After a restart plus a storage
 *   failure there is no last-known restriction and the tick is skipped.
 *
 * Everything runs on [scope], which must be single threaded (the service uses `Dispatchers.Main.immediate`); the
 * state below is not synchronized.
 */
class ForegroundEnforcer(
    private val scope: CoroutineScope,
    private val evaluateForegroundApp: EvaluateForegroundApp,
    private val observeEnforcedPackages: ObserveEnforcedPackages,
    private val getForegroundPackage: GetForegroundPackage,
    private val filter: ForegroundFilter,
    private val blockScreenLauncher: BlockScreenLauncher,
    private val screenState: ScreenState,
    private val windowVisibility: WindowVisibility,
    private val nowMillis: () -> Long,
    private val logFailure: (message: String, cause: Throwable) -> Unit,
    private val timing: EnforcerTiming = EnforcerTiming(),
) {

    private var enforcedPackages: Set<String>? = null
    private var observerJob: Job? = null

    private var loopJob: Job? = null
    private var loopTarget: String? = null
    private var loopSinceMillis: Long? = null
    private var lastSeenPackage: String? = null

    /** Bumped whenever a loop starts, stops or changes target; lets a suspended read detect it is stale. */
    private var loopGeneration = 0
    private var pendingReevaluationJob: Job? = null

    private var confirmationJob: Job? = null
    private var blockScreenConfirmed = false
    private val lastRestrictionByPackage = mutableMapOf<String, ForegroundAppVerdict.Restricted>()

    private val isLooping: Boolean get() = loopJob?.isActive == true

    /** Starts following the enforced packages and evaluates whatever is already in the foreground. */
    fun start() {
        observerJob?.cancel()
        observerJob = scope.launch { observeEnforcedSet() }
        scope.launch { reevaluateForeground() }
    }

    fun stop() {
        observerJob?.cancel()
        stopLoop()
        loopTarget = null
        lastSeenPackage = null
        lastRestrictionByPackage.clear()
    }

    /** A window of [packageName] came to the front (TYPE_WINDOW_STATE_CHANGED). Never throws. */
    fun onWindowChanged(packageName: String) {
        try {
            handleWindowChanged(packageName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logFailure("The window event of $packageName could not be handled", failure)
        }
    }

    private fun handleWindowChanged(packageName: String) {
        val kind = kindOf(packageName)
        if (kind == ForegroundKind.TRANSIENT) {
            // The event of the app itself may have been swallowed by this overlay: look at the real foreground.
            if (!isLooping) scheduleReevaluation()
            return
        }
        val previousPackage = lastSeenPackage
        lastSeenPackage = packageName
        when {
            kind == ForegroundKind.OWN_APP -> {
                blockScreenConfirmed = true
                cancelConfirmation()
            }
            isCandidate(packageName, kind) ->
                if (!isLooping || loopTarget != packageName || previousPackage != packageName) {
                    startLoop(packageName, usageSinceMillis = null)
                }
            isLooping -> scope.launch { confirmDeparture() }
            kind == ForegroundKind.APP && !isLaunchable(packageName) -> scheduleReevaluation()
        }
    }

    /** The screen turned on or the user unlocked the device: look at the foreground again. */
    fun onDeviceAwake() {
        scope.launch { reevaluateForeground() }
    }

    private suspend fun observeEnforcedSet() {
        var backoffMillis = timing.retryInitialMillis
        while (true) {
            try {
                observeEnforcedPackages().collect { packages ->
                    backoffMillis = timing.retryInitialMillis
                    enforcedPackages = packages
                    scope.launch { reevaluateForeground() }
                }
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                logFailure("The enforced packages could not be observed; retrying", failure)
                delay(backoffMillis)
                backoffMillis = (backoffMillis * 2).coerceAtMost(timing.retryMaxMillis)
            }
        }
    }

    /** Asks for one re-evaluation of the foreground; while one is already pending, further requests join it. */
    private fun scheduleReevaluation() {
        if (pendingReevaluationJob?.isActive == true) return
        pendingReevaluationJob = scope.launch { reevaluateForeground() }
    }

    private suspend fun reevaluateForeground() {
        // The read suspends: if a loop was started or stopped meanwhile, the snapshot is older than that decision.
        val generationBeforeRead = loopGeneration
        val foreground = readForeground() ?: return
        if (generationBeforeRead != loopGeneration) return
        if (isLooping && loopTarget == foreground.packageName) return
        if (isCandidate(foreground.packageName, kindOf(foreground.packageName))) {
            startLoop(foreground.packageName, foreground.foregroundSinceMillis)
        }
    }

    /** An enforced package, or any launchable app while the enforced set is still unknown. */
    private fun isCandidate(packageName: String, kind: ForegroundKind): Boolean =
        kind == ForegroundKind.APP && (enforcedPackages?.contains(packageName) ?: isLaunchable(packageName))

    /** The filter talks to system services that can throw (binder): a failure is logged and treated as an overlay. */
    private fun kindOf(packageName: String): ForegroundKind = try {
        filter.kindOf(packageName)
    } catch (failure: Exception) {
        logFailure("The kind of $packageName could not be determined", failure)
        ForegroundKind.TRANSIENT
    }

    private fun isLaunchable(packageName: String): Boolean = try {
        filter.isLaunchable(packageName)
    } catch (failure: Exception) {
        logFailure("The launcher icon of $packageName could not be checked", failure)
        false
    }

    private fun isVisible(packageName: String): Boolean = try {
        windowVisibility.isPackageVisible(packageName)
    } catch (failure: Exception) {
        logFailure("The windows on screen could not be read", failure)
        false
    }

    private fun startLoop(target: String, usageSinceMillis: Long?) {
        loopGeneration++
        loopJob?.cancel()
        cancelConfirmation()
        blockScreenConfirmed = false
        loopTarget = target
        loopSinceMillis = usageSinceMillis
        loopJob = scope.launch { runLoop() }
    }

    private fun stopLoop() {
        loopGeneration++
        loopJob?.cancel()
        cancelConfirmation()
    }

    private suspend fun runLoop() {
        var firstTick = true
        while (screenState.isInteractiveAndUnlocked()) {
            val keepGoing = try {
                tick(firstTick)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                logFailure("An enforcement tick failed", failure)
                true
            }
            if (!keepGoing) break
            firstTick = false
            delay(timing.tickMillis)
        }
        cancelConfirmation()
    }

    /** One pass of the loop. Returns false when the loop should end. */
    private suspend fun tick(firstTick: Boolean): Boolean {
        var target = loopTarget ?: return false
        val usageAlreadyKnown = firstTick && loopSinceMillis != null
        val foreground = if (usageAlreadyKnown) null else readForeground()
        currentCoroutineContext().ensureActive()

        // The first tick trusts the window event: usage may not list the new app yet.
        if (!firstTick && foreground != null && foreground.packageName != target) {
            when (judge(foreground, target)) {
                Judgement.OVERLAY -> return true
                Judgement.STOP -> return false
                Judgement.SWITCH -> target = switchTarget(foreground)
                Judgement.KEEP -> Unit
            }
        }
        if (foreground?.packageName == target) loopSinceMillis = foreground.foregroundSinceMillis
        val sinceMillis = loopSinceMillis ?: nowMillis().also { loopSinceMillis = it }
        return evaluateAndEnforce(target, sinceMillis)
    }

    private fun judge(foreground: ForegroundApp, target: String): Judgement {
        val packageName = foreground.packageName
        val kind = kindOf(packageName)
        return when (kind) {
            ForegroundKind.TRANSIENT -> Judgement.OVERLAY
            ForegroundKind.OWN_APP -> Judgement.STOP
            ForegroundKind.LAUNCHER -> stopUnlessVisible(target)
            ForegroundKind.APP -> when {
                isCandidate(packageName, kind) -> Judgement.SWITCH
                isLaunchable(packageName) -> stopUnlessVisible(target)
                else -> Judgement.OVERLAY
            }
        }
    }

    /** Usage reports only the latest app: in split screen the target can still be on screen next to it. */
    private fun stopUnlessVisible(target: String): Judgement =
        if (isVisible(target)) Judgement.KEEP else Judgement.STOP

    private fun switchTarget(foreground: ForegroundApp): String {
        loopGeneration++
        cancelConfirmation()
        blockScreenConfirmed = false
        loopTarget = foreground.packageName
        loopSinceMillis = foreground.foregroundSinceMillis
        return foreground.packageName
    }

    /** A window that is not an enforced app came up: end the loop only if usage confirms the user left. */
    private suspend fun confirmDeparture() {
        val target = loopTarget ?: return
        val foreground = readForeground() ?: return
        if (foreground.packageName == target) return
        when (judge(foreground, target)) {
            Judgement.STOP -> stopLoop()
            Judgement.SWITCH -> startLoop(foreground.packageName, foreground.foregroundSinceMillis)
            Judgement.OVERLAY, Judgement.KEEP -> Unit
        }
    }

    private suspend fun evaluateAndEnforce(target: String, sinceMillis: Long): Boolean {
        val verdict = try {
            evaluateForegroundApp(target, sinceMillis)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logFailure("Evaluating $target failed", failure)
            return enforceLastKnownRestriction(target)
        }
        currentCoroutineContext().ensureActive()
        return when (verdict) {
            ForegroundAppVerdict.NotEnforced -> {
                lastRestrictionByPackage.remove(target)
                false
            }
            is ForegroundAppVerdict.Allowed -> {
                lastRestrictionByPackage.remove(target)
                true
            }
            is ForegroundAppVerdict.Restricted -> {
                lastRestrictionByPackage[target] = verdict
                showBlockScreen(target)
            }
        }
    }

    /** Fail closed: a restriction that has not ended keeps blocking even when storage cannot be read. */
    private fun enforceLastKnownRestriction(target: String): Boolean {
        val lastRestriction = lastRestrictionByPackage[target]
        return if (lastRestriction != null && lastRestriction.untilMillis > nowMillis()) {
            showBlockScreen(target)
        } else {
            true
        }
    }

    /** Launches the block screen and arms the home fallback. Returns false when the device cannot show it now. */
    private fun showBlockScreen(target: String): Boolean {
        if (!screenState.isInteractiveAndUnlocked()) return false
        cancelConfirmation()
        blockScreenConfirmed = false
        try {
            blockScreenLauncher.showBlockScreen(target)
        } catch (failure: Exception) {
            logFailure("The block screen for $target could not be launched", failure)
        }
        confirmationJob = scope.launch {
            delay(timing.launchConfirmMillis)
            if (!blockScreenConfirmed && targetStillInFront(target)) blockScreenLauncher.goHome()
        }
        return true
    }

    /** The home fallback is only for a target the user still sees; the foreground may have changed in 1.5 s. */
    private suspend fun targetStillInFront(target: String): Boolean {
        val foreground = readForeground()
        currentCoroutineContext().ensureActive()
        return foreground?.packageName == target || isVisible(target)
    }

    private fun cancelConfirmation() {
        confirmationJob?.cancel()
        confirmationJob = null
    }

    private suspend fun readForeground(): ForegroundApp? = try {
        getForegroundPackage()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        logFailure("The foreground app could not be read", failure)
        null
    }

    private enum class Judgement {
        /** Something is on top of the app but the user did not leave it. */
        OVERLAY,

        /** The user left: end the loop. */
        STOP,

        /** Another enforced app is in front: follow it. */
        SWITCH,

        /** Usage points elsewhere but the target is still on screen (split screen): keep enforcing it. */
        KEEP,
    }
}

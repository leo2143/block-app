package com.leo.blockapp.adapters.input.service.enforcer

import com.leo.blockapp.adapters.input.service.enforcer.FakeEvaluateForegroundApp.Call
import com.leo.blockapp.domain.model.BlockReason
import com.leo.blockapp.domain.model.ForegroundApp
import com.leo.blockapp.domain.model.ForegroundAppVerdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundEnforcerTest {

    private val ownPackage = "com.leo.blockapp"
    private val instagram = "com.instagram.android"
    private val x = "com.twitter.android"
    private val whatsapp = "com.whatsapp"
    private val launcherPackage = "com.launcher"
    private val statusBar = "com.android.systemui"
    private val chooser = "android"
    private val vendorDialog = "com.vendor.dialog"
    private val baseTimeMillis = 1_000_000_000L
    private val tickMillis = 5_000L

    private inner class Harness(testScope: TestScope) {
        val evaluate = FakeEvaluateForegroundApp()
        val foreground = FakeGetForegroundPackage()
        val enforced = FakeObserveEnforcedPackages()
        val launcher = RecordingBlockScreenLauncher()
        val screen = FakeScreenState()
        val visibility = FakeWindowVisibility()
        var filterFailure: Throwable? = null
        val failureMessages = mutableListOf<String>()
        val launchablePackages = mutableSetOf(instagram, x, whatsapp)

        private val filter = ForegroundFilter(
            ownPackage = ownPackage,
            inputMethodPackages = {
                filterFailure?.let { throw it }
                emptySet()
            },
            launcherPackages = { setOf(launcherPackage) },
            hasLauncherIcon = { it in launchablePackages },
        )

        val enforcer = ForegroundEnforcer(
            scope = testScope.backgroundScope,
            evaluateForegroundApp = evaluate,
            observeEnforcedPackages = enforced,
            getForegroundPackage = foreground,
            filter = filter,
            blockScreenLauncher = launcher,
            screenState = screen,
            windowVisibility = visibility,
            nowMillis = { baseTimeMillis + testScope.testScheduler.currentTime },
            logFailure = { message, _ -> failureMessages += message },
        )

        /** Starts the enforcer; [enforcedSet] null leaves the enforced set silent (storage not readable yet). */
        fun start(enforcedSet: Set<String>? = setOf(instagram, x)) {
            enforcer.start()
            enforcedSet?.let { enforced.emissions.tryEmit(it) }
        }

        fun restricted(untilMillis: Long = baseTimeMillis + HOUR_MILLIS, reason: BlockReason = BlockReason.COOLDOWN) =
            ForegroundAppVerdict.Restricted("B1", untilMillis, reason)
    }

    private fun TestScope.harness(enforcedSet: Set<String>? = setOf(instagram, x)): Harness =
        Harness(this).also {
            it.start(enforcedSet)
            runCurrent()
        }

    private suspend fun TestScope.passTicks(count: Int) {
        repeat(count) {
            advanceTimeBy(tickMillis)
            runCurrent()
        }
    }

    // --- Evaluation loop (F4) ---

    @Test
    fun `an enforced app coming to the foreground is evaluated at once with the usage timestamp`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)

        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertEquals(listOf(Call(instagram, 1_234)), harness.evaluate.calls)
    }

    @Test
    fun `when usage does not know the app yet it is evaluated as just opened`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(launcherPackage, 50)

        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertEquals(listOf(Call(instagram, baseTimeMillis)), harness.evaluate.calls)
    }

    @Test
    fun `a timestamp is never paired with another package`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)

        harness.enforcer.onWindowChanged(x)
        runCurrent()

        assertEquals(listOf(Call(x, baseTimeMillis)), harness.evaluate.calls)
    }

    @Test
    fun `the app is evaluated again every five seconds while it stays in the foreground`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        advanceTimeBy(tickMillis - 1)
        runCurrent()
        assertEquals(1, harness.evaluate.calls.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, harness.evaluate.calls.size)

        passTicks(1)
        assertEquals(listOf(Call(instagram, 1_234), Call(instagram, 1_234), Call(instagram, 1_234)), harness.evaluate.calls)
    }

    @Test
    fun `a limit crossed between ticks shows the block screen on the next tick`() = runTest {
        val harness = harness()
        var evaluations = 0
        harness.evaluate.verdictFor = {
            if (++evaluations == 1) ForegroundAppVerdict.Allowed("B1", 4_000) else harness.restricted()
        }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        assertTrue(harness.launcher.launches.isEmpty())

        passTicks(1)

        assertEquals(listOf(instagram), harness.launcher.launches)
    }

    @Test
    fun `in-app window events of the same app do not evaluate again`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)

        harness.enforcer.onWindowChanged(instagram)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertEquals(1, harness.evaluate.calls.size)
    }

    @Test
    fun `an app that is in no enforced block is not evaluated`() = runTest {
        val harness = harness(setOf(instagram))
        harness.foreground.app = ForegroundApp(whatsapp, 7)

        harness.enforcer.onWindowChanged(whatsapp)
        runCurrent()
        passTicks(2)

        assertTrue(harness.evaluate.calls.isEmpty())
    }

    @Test
    fun `an update of the enforced set evaluates the app already in the foreground`() = runTest {
        val harness = harness(setOf(x))
        harness.foreground.app = ForegroundApp(instagram, 5)
        assertTrue(harness.evaluate.calls.isEmpty())

        harness.enforced.emissions.tryEmit(setOf(x, instagram))
        runCurrent()

        assertEquals(listOf(Call(instagram, 5)), harness.evaluate.calls)
    }

    @Test
    fun `before the first emission any launchable foreground app is evaluated`() = runTest {
        val harness = harness(enforcedSet = null)
        harness.evaluate.verdictFor = { ForegroundAppVerdict.NotEnforced }
        harness.foreground.app = ForegroundApp(whatsapp, 7)

        harness.enforcer.onWindowChanged(whatsapp)
        runCurrent()

        assertEquals(listOf(Call(whatsapp, 7)), harness.evaluate.calls)
    }

    @Test
    fun `before the first emission an app with no launcher icon is not evaluated`() = runTest {
        val harness = harness(enforcedSet = null)
        harness.foreground.app = ForegroundApp(vendorDialog, 7)

        harness.enforcer.onWindowChanged(vendorDialog)
        runCurrent()

        assertTrue(harness.evaluate.calls.isEmpty())
    }

    @Test
    fun `a not enforced verdict ends the loop`() = runTest {
        val harness = harness(enforcedSet = null)
        harness.evaluate.verdictFor = { ForegroundAppVerdict.NotEnforced }
        harness.foreground.app = ForegroundApp(whatsapp, 7)
        harness.enforcer.onWindowChanged(whatsapp)
        runCurrent()

        passTicks(3)

        assertEquals(1, harness.evaluate.calls.size)
    }

    @Test
    fun `an app already in the foreground when the service connects is evaluated without any event`() = runTest {
        val harness = Harness(this)
        harness.foreground.app = ForegroundApp(instagram, 77)

        harness.start(setOf(instagram))
        runCurrent()

        assertEquals(listOf(Call(instagram, 77)), harness.evaluate.calls)
    }

    @Test
    fun `stop ends every loop`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.enforcer.stop()
        passTicks(3)

        assertEquals(1, harness.evaluate.calls.size)
    }

    // --- Block screen launch (F4) ---

    @Test
    fun `a restricted verdict launches the block screen`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)

        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertEquals(listOf(instagram), harness.launcher.launches)
        assertEquals(0, harness.launcher.homeCount)
    }

    @Test
    fun `a launcher that throws is logged and the home fallback still runs`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.launcher.launchFailure = IllegalStateException("no activity")
        harness.foreground.app = ForegroundApp(instagram, 1_234)

        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(1, harness.failureMessages.size)
        assertEquals(1, harness.launcher.homeCount)
    }

    @Test
    fun `an allowed verdict never launches the block screen`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)

        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        passTicks(2)

        assertTrue(harness.launcher.launches.isEmpty())
    }

    @Test
    fun `the launch is confirmed by a window of the own package`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        advanceTimeBy(1_000)
        harness.enforcer.onWindowChanged(ownPackage, isBlockScreen = true)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(0, harness.launcher.homeCount)
    }

    @Test
    fun `an unconfirmed launch falls back to the home action after 1500 ms`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        advanceTimeBy(1_499)
        runCurrent()
        assertEquals(0, harness.launcher.homeCount)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, harness.launcher.homeCount)
    }

    @Test
    fun `while the app stays in front and the launch is unconfirmed the block screen is launched again every tick`() =
        runTest {
            val harness = harness()
            harness.evaluate.verdictFor = { harness.restricted() }
            harness.foreground.app = ForegroundApp(instagram, 1_234)
            harness.enforcer.onWindowChanged(instagram)
            runCurrent()

            passTicks(2)

            assertEquals(listOf(instagram, instagram, instagram), harness.launcher.launches)
        }

    @Test
    fun `once the block screen is the foreground the loop stops relaunching and evaluating`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        harness.enforcer.onWindowChanged(ownPackage, isBlockScreen = true)
        harness.foreground.app = ForegroundApp(ownPackage, baseTimeMillis)

        passTicks(4)

        assertEquals(listOf(instagram), harness.launcher.launches)
        assertEquals(1, harness.evaluate.calls.size)
        assertEquals(0, harness.launcher.homeCount)
    }

    @Test
    fun `after the block screen closes the next event of the app evaluates again`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(ownPackage, baseTimeMillis)
        harness.enforcer.onWindowChanged(ownPackage, isBlockScreen = true)
        passTicks(1)

        harness.foreground.app = ForegroundApp(instagram, 9_999)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertEquals(listOf(Call(instagram, 9_999)), harness.evaluate.calls)
        assertEquals(listOf(instagram), harness.launcher.launches)
    }

    // --- Neutral and transient windows (F2) ---

    @Test
    fun `a transient window neither evaluates nor cancels the loop`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.enforcer.onWindowChanged(statusBar)
        runCurrent()
        passTicks(1)

        assertEquals(listOf(instagram, instagram), harness.evaluate.calls.map { it.packageName })
    }

    @Test
    fun `an overlay over the app pauses the evaluation and returning to the app resumes it`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = ForegroundApp(chooser, 5_000)
        passTicks(2)
        assertEquals(1, harness.evaluate.calls.size)

        harness.foreground.app = ForegroundApp(instagram, 1_234)
        passTicks(1)
        assertEquals(2, harness.evaluate.calls.size)
    }

    @Test
    fun `an app with no launcher icon in front does not cancel the loop`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = ForegroundApp(vendorDialog, 5_000)
        passTicks(1)
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        passTicks(1)

        assertEquals(2, harness.evaluate.calls.size)
    }

    @Test
    fun `going home cancels the loop`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = ForegroundApp(launcherPackage, 8_000)
        harness.enforcer.onWindowChanged(launcherPackage)
        runCurrent()
        passTicks(4)

        assertEquals(1, harness.evaluate.calls.size)
    }

    @Test
    fun `a home window is not enough while usage still reports the app, the next tick decides`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.enforcer.onWindowChanged(launcherPackage)
        runCurrent()
        passTicks(1)
        assertEquals(2, harness.evaluate.calls.size)

        harness.foreground.app = ForegroundApp(launcherPackage, 8_000)
        passTicks(3)
        assertEquals(2, harness.evaluate.calls.size)
    }

    @Test
    fun `reopening the app after going home evaluates it at once`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        harness.foreground.app = ForegroundApp(launcherPackage, 8_000)
        harness.enforcer.onWindowChanged(launcherPackage)
        runCurrent()

        harness.foreground.app = ForegroundApp(instagram, 9_000)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertEquals(listOf(Call(instagram, 1_234), Call(instagram, 9_000)), harness.evaluate.calls)
    }

    @Test
    fun `another app that is not enforced cancels the loop once usage confirms it`() = runTest {
        val harness = harness(setOf(instagram))
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        passTicks(4)

        assertEquals(listOf(Call(instagram, 1_234)), harness.evaluate.calls)
    }

    @Test
    fun `switching to another enforced app moves the loop to it with its own timestamp`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = ForegroundApp(x, 8_000)
        passTicks(2)

        assertEquals(
            listOf(Call(instagram, 1_234), Call(x, 8_000), Call(x, 8_000)),
            harness.evaluate.calls,
        )
    }

    @Test
    fun `the own package in front suspends the loop and the next event restarts it`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = ForegroundApp(ownPackage, 8_000)
        passTicks(3)
        assertEquals(1, harness.evaluate.calls.size)

        harness.foreground.app = ForegroundApp(instagram, 20_000)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        assertEquals(Call(instagram, 20_000), harness.evaluate.calls.last())
    }

    // --- Screen and keyguard (F4) ---

    @Test
    fun `nothing is evaluated or launched while the device is locked`() = runTest {
        val harness = harness()
        harness.screen.interactiveAndUnlocked = false
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)

        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        passTicks(2)

        assertTrue(harness.evaluate.calls.isEmpty())
        assertTrue(harness.launcher.launches.isEmpty())
    }

    @Test
    fun `unlocking re-evaluates the app that is in the foreground`() = runTest {
        val harness = harness()
        harness.screen.interactiveAndUnlocked = false
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.screen.interactiveAndUnlocked = true
        harness.enforcer.onDeviceAwake()
        runCurrent()

        assertEquals(listOf(Call(instagram, 1_234)), harness.evaluate.calls)
        assertEquals(listOf(instagram), harness.launcher.launches)
    }

    @Test
    fun `waking the device with another app in front does not evaluate it when it is not enforced`() = runTest {
        val harness = harness(setOf(instagram))
        harness.foreground.app = ForegroundApp(whatsapp, 1_234)

        harness.enforcer.onDeviceAwake()
        runCurrent()

        assertTrue(harness.evaluate.calls.isEmpty())
    }

    @Test
    fun `the screen turning off stops the polling until the device wakes`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.screen.interactiveAndUnlocked = false
        passTicks(3)
        assertEquals(1, harness.evaluate.calls.size)

        harness.screen.interactiveAndUnlocked = true
        harness.enforcer.onDeviceAwake()
        runCurrent()
        assertEquals(2, harness.evaluate.calls.size)
    }

    @Test
    fun `a restricted verdict is not launched if the device locked while evaluating`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = {
            harness.screen.interactiveAndUnlocked = false
            harness.restricted()
        }
        harness.foreground.app = ForegroundApp(instagram, 1_234)

        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertTrue(harness.launcher.launches.isEmpty())
        assertEquals(1, harness.evaluate.calls.size)
    }

    // --- Error policy (F4) ---

    @Test
    fun `a storage failure with no known restriction skips the evaluation and retries on the next tick`() = runTest {
        val harness = harness()
        harness.evaluate.failure = IOException("disk")
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertTrue(harness.launcher.launches.isEmpty())
        assertEquals(1, harness.failureMessages.size)

        harness.evaluate.failure = null
        passTicks(1)
        assertEquals(2, harness.evaluate.calls.size)
    }

    @Test
    fun `a storage failure keeps blocking an app that was restricted until later`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted(untilMillis = baseTimeMillis + HOUR_MILLIS) }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        harness.enforcer.onWindowChanged(ownPackage, isBlockScreen = true)
        runCurrent()
        assertEquals(1, harness.launcher.launches.size)

        harness.evaluate.failure = IOException("disk")
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertEquals(listOf(instagram, instagram), harness.launcher.launches)
        assertEquals(1, harness.failureMessages.size)
    }

    @Test
    fun `a storage failure does not block an app whose restriction already ended`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted(untilMillis = baseTimeMillis + 1_000) }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        harness.enforcer.onWindowChanged(ownPackage, isBlockScreen = true)
        advanceTimeBy(2_000)
        runCurrent()

        harness.evaluate.failure = IOException("disk")
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertEquals(listOf(instagram), harness.launcher.launches)
    }

    @Test
    fun `a security failure is logged and does not crash the loop`() = runTest {
        val harness = harness()
        harness.evaluate.failure = SecurityException("usage access revoked")
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.evaluate.failure = null
        passTicks(1)

        assertEquals(1, harness.failureMessages.size)
        assertEquals(2, harness.evaluate.calls.size)
    }

    @Test
    fun `an unexpected failure is logged and does not crash the loop`() = runTest {
        val harness = harness()
        harness.evaluate.failure = IllegalStateException("bug")
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.evaluate.failure = null
        passTicks(1)

        assertEquals(1, harness.failureMessages.size)
        assertEquals(2, harness.evaluate.calls.size)
    }

    @Test
    fun `when the foreground query fails the event package is evaluated as just opened`() = runTest {
        val harness = harness()
        harness.foreground.failure = SecurityException("usage access revoked")

        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertEquals(listOf(Call(instagram, baseTimeMillis)), harness.evaluate.calls)
        assertTrue(harness.failureMessages.isNotEmpty())
    }

    @Test
    fun `a failing foreground query on a later tick keeps the last known timestamp`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.visibility.visiblePackages += instagram
        harness.foreground.failure = IOException("events unavailable")
        passTicks(1)

        assertEquals(listOf(Call(instagram, 1_234), Call(instagram, 1_234)), harness.evaluate.calls)
    }

    // --- Split screen ---

    @Test
    fun `a blocked app still visible in split screen keeps the loop and keeps blocking`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        harness.enforcer.onWindowChanged(ownPackage, isBlockScreen = true)

        harness.visibility.visiblePackages += instagram
        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        passTicks(2)

        assertEquals(3, harness.evaluate.calls.size)
        assertEquals(listOf(instagram, instagram, instagram), harness.launcher.launches)
    }

    @Test
    fun `a window event of the other pane does not end the loop of the visible app`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.visibility.visiblePackages += instagram
        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        harness.enforcer.onWindowChanged(whatsapp)
        runCurrent()
        passTicks(2)

        assertEquals(3, harness.evaluate.calls.size)
    }

    @Test
    fun `a normal departure with the app not visible still stops the loop`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        passTicks(3)

        assertEquals(1, harness.evaluate.calls.size)
    }

    @Test
    fun `a failing visibility check counts as not visible`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.visibility.failure = IllegalStateException("windows unavailable")
        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        passTicks(3)

        assertEquals(1, harness.evaluate.calls.size)
        assertTrue(harness.failureMessages.isNotEmpty())
    }

    // --- Swallowed and transient events ---

    @Test
    fun `a transient event while idle recovers a restricted app that is actually in front`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)

        harness.enforcer.onWindowChanged(statusBar)
        runCurrent()

        assertEquals(listOf(Call(instagram, 1_234)), harness.evaluate.calls)
        assertEquals(listOf(instagram), harness.launcher.launches)
    }

    @Test
    fun `several transient events while idle share one re-evaluation`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(whatsapp, 1_234)
        val queriesBefore = harness.foreground.queries

        harness.enforcer.onWindowChanged(statusBar)
        harness.enforcer.onWindowChanged(statusBar)
        harness.enforcer.onWindowChanged(chooser)
        runCurrent()

        assertEquals(queriesBefore + 1, harness.foreground.queries)
    }

    // --- Stale snapshot race ---

    @Test
    fun `a stale foreground snapshot does not replace the loop of an app that arrived meanwhile`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { if (it == instagram) harness.restricted() else ForegroundAppVerdict.Allowed("B1", 60_000) }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.foreground.onNextQuery = { delay(100) }
        harness.enforcer.onDeviceAwake()
        runCurrent()

        harness.foreground.app = ForegroundApp(x, 5_000)
        harness.enforcer.onWindowChanged(x)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        passTicks(1)

        assertTrue(harness.launcher.launches.isEmpty())
        assertTrue(harness.evaluate.calls.all { it.packageName == x })
    }

    // --- Home fallback confirmation ---

    @Test
    fun `the home fallback does not fire when the user already left to a not enforced app`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(0, harness.launcher.homeCount)
    }

    @Test
    fun `the home fallback does not fire when the block screen is already in front`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = ForegroundApp(ownPackage, 8_000)
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(0, harness.launcher.homeCount)
    }

    @Test
    fun `the home fallback still fires when the foreground cannot be read but the app is visible`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = null
        harness.visibility.visiblePackages += instagram
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(1, harness.launcher.homeCount)
    }

    @Test
    fun `a departure to another app cancels the pending home fallback`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        harness.enforcer.onWindowChanged(whatsapp)
        runCurrent()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(0, harness.launcher.homeCount)
    }

    @Test
    fun `an unreadable foreground with an unconfirmed launch counts as still in front and goes home`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = null
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(1, harness.launcher.homeCount)
    }

    @Test
    fun `an unreadable foreground and a failing visibility check still go home after an unconfirmed launch`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.failure = IllegalStateException("usage unavailable")
        harness.visibility.failure = IllegalStateException("windows unavailable")
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(1, harness.launcher.homeCount)
    }

    @Test
    fun `the home fallback does not fire when the block screen is in front even if the app is visible`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.visibility.visiblePackages += instagram
        harness.foreground.app = ForegroundApp(ownPackage, 8_000)
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(0, harness.launcher.homeCount)
    }

    @Test
    fun `a restricted tick while the block screen is already in front does not relaunch it or go home`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        harness.enforcer.onWindowChanged(ownPackage, isBlockScreen = true)

        // Usage still lags on the target and the target is visible next to the block screen (split screen).
        harness.visibility.visiblePackages += instagram
        passTicks(3)

        assertEquals(listOf(instagram), harness.launcher.launches)
        assertEquals(0, harness.launcher.homeCount)
    }

    // --- Split screen at start ---

    @Test
    fun `an enforced app restored in the other pane is enforced after the device wakes`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        harness.visibility.visiblePackages += instagram

        harness.enforcer.onDeviceAwake()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf(instagram), harness.evaluate.calls.map { it.packageName })
        assertEquals(listOf(instagram), harness.launcher.launches)
    }

    @Test
    fun `an enforced app visible in the other pane when the service starts is enforced without any event`() = runTest {
        val harness = Harness(this)
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        harness.visibility.visiblePackages += instagram

        harness.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf(instagram), harness.evaluate.calls.map { it.packageName })
        assertEquals(listOf(instagram), harness.launcher.launches)
    }

    @Test
    fun `a visible app that is not enforced is not evaluated`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        harness.visibility.visiblePackages += whatsapp

        harness.enforcer.onDeviceAwake()
        runCurrent()

        assertTrue(harness.evaluate.calls.isEmpty())
    }

    @Test
    fun `a failing window query while looking for a visible enforced app is logged and ignored`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        harness.visibility.failure = IllegalStateException("windows unavailable")

        harness.enforcer.onDeviceAwake()
        runCurrent()

        assertTrue(harness.evaluate.calls.isEmpty())
        assertTrue(harness.failureMessages.isNotEmpty())
    }

    // --- Stale departure snapshot ---

    @Test
    fun `a stale departure snapshot does not stop a loop that switched to another enforced app meanwhile`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        harness.foreground.onNextQuery = { delay(100) }
        harness.enforcer.onWindowChanged(whatsapp)
        runCurrent()

        harness.foreground.app = ForegroundApp(x, 9_000)
        harness.enforcer.onWindowChanged(x)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        passTicks(1)

        assertEquals(listOf(instagram, x, x), harness.evaluate.calls.map { it.packageName })
    }

    @Test
    fun `departure checks that arrive while one is reading share a single extra read`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        val queriesBefore = harness.foreground.queries

        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        harness.foreground.onNextQuery = { delay(100) }
        harness.enforcer.onWindowChanged(whatsapp)
        runCurrent()
        harness.enforcer.onWindowChanged(whatsapp)
        harness.enforcer.onWindowChanged(whatsapp)
        advanceTimeBy(200)
        runCurrent()

        assertEquals(queriesBefore + 2, harness.foreground.queries)
    }

    // --- Filter failures ---

    @Test
    fun `a filter that throws on a window event is logged and does not crash`() = runTest {
        val harness = harness()
        harness.filterFailure = IllegalStateException("package manager died")

        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        assertTrue(harness.failureMessages.isNotEmpty())
        assertTrue(harness.evaluate.calls.isEmpty())
    }

    @Test
    fun `a filter that throws during a tick is logged and the loop continues`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.filterFailure = IllegalStateException("package manager died")
        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        passTicks(1)
        assertTrue(harness.failureMessages.isNotEmpty())
        assertEquals(1, harness.evaluate.calls.size)

        harness.filterFailure = null
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        passTicks(1)
        assertEquals(2, harness.evaluate.calls.size)
    }

    // --- Start race (F3) ---

    @Test
    fun `a failing enforced set is retried with a growing backoff`() = runTest {
        val harness = Harness(this)
        harness.enforced.failuresBeforeSuccess = 2
        harness.foreground.app = ForegroundApp(instagram, 77)
        harness.enforcer.start()
        runCurrent()
        assertEquals(1, harness.enforced.collections)

        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, harness.enforced.collections)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, harness.enforced.collections)

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, harness.enforced.collections)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, harness.enforced.collections)
        assertEquals(2, harness.failureMessages.size)
    }

    @Test
    fun `the enforced set works again once the retry succeeds`() = runTest {
        val harness = Harness(this)
        harness.enforced.failuresBeforeSuccess = 1
        harness.enforcer.start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        harness.foreground.app = ForegroundApp(instagram, 5)
        harness.enforced.emissions.tryEmit(setOf(instagram))
        runCurrent()

        assertEquals(listOf(Call(instagram, 5)), harness.evaluate.calls)
    }

    // --- Block screen detection by its own window ---

    @Test
    fun `a usage reading of the own app while the last window event is the target still launches the block screen`() =
        runTest {
            val harness = harness()
            harness.evaluate.verdictFor = { harness.restricted() }
            harness.foreground.app = ForegroundApp(ownPackage, 100)

            harness.enforcer.onWindowChanged(instagram)
            runCurrent()

            assertEquals(listOf(instagram), harness.launcher.launches)
        }

    @Test
    fun `a window of the own package that is not the block screen does not cancel the home fallback`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        advanceTimeBy(1_000)
        harness.enforcer.onWindowChanged(ownPackage)
        advanceTimeBy(600)
        runCurrent()

        assertEquals(1, harness.launcher.homeCount)
    }

    // --- Unreadable foreground ---

    @Test
    fun `an unreadable foreground with the target not visible after a launcher event stops the loop`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = null
        harness.enforcer.onWindowChanged(launcherPackage)
        runCurrent()
        passTicks(3)

        assertEquals(listOf(instagram), harness.launcher.launches)
        assertEquals(1, harness.evaluate.calls.size)
    }

    @Test
    fun `an unreadable foreground with the target visible after a launcher event keeps the loop`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.visibility.visiblePackages += instagram
        harness.foreground.app = null
        harness.enforcer.onWindowChanged(launcherPackage)
        runCurrent()
        passTicks(2)

        assertEquals(3, harness.evaluate.calls.size)
    }

    @Test
    fun `an unreadable foreground on a tick with the target not visible stops the loop`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        harness.foreground.app = null
        passTicks(3)

        assertEquals(1, harness.evaluate.calls.size)
    }

    // --- Visibility fallback ---

    @Test
    fun `a transient event while the departed app window is still listed does not restart a loop`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()
        harness.foreground.app = ForegroundApp(launcherPackage, 9_000)
        harness.enforcer.onWindowChanged(launcherPackage)
        runCurrent()

        harness.visibility.visiblePackages += instagram
        harness.enforcer.onWindowChanged(statusBar)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, harness.evaluate.calls.size)
        assertEquals(listOf(instagram), harness.launcher.launches)
    }

    @Test
    fun `an enforced app visible only once is not enforced`() = runTest {
        val harness = harness()
        harness.foreground.app = ForegroundApp(whatsapp, 8_000)
        harness.visibility.visiblePackages += instagram

        harness.enforcer.onDeviceAwake()
        runCurrent()
        harness.visibility.visiblePackages.clear()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(harness.evaluate.calls.isEmpty())
    }

    // --- Screen state at the home fallback ---

    @Test
    fun `the device locking within the confirmation window does not send the user home`() = runTest {
        val harness = harness()
        harness.evaluate.verdictFor = { harness.restricted() }
        harness.foreground.app = ForegroundApp(instagram, 1_234)
        harness.enforcer.onWindowChanged(instagram)
        runCurrent()

        advanceTimeBy(1_000)
        harness.screen.interactiveAndUnlocked = false
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(0, harness.launcher.homeCount)
    }

    private companion object {
        const val HOUR_MILLIS = 60 * 60 * 1000L
    }
}

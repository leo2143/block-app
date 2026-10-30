package com.leo.blockapp.adapters.input.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.leo.blockapp.adapters.input.service.enforcer.ForegroundEnforcer
import com.leo.blockapp.adapters.input.service.enforcer.WindowVisibility
import com.leo.blockapp.domain.ports.input.EvaluateForegroundApp
import com.leo.blockapp.domain.ports.input.GetForegroundPackage
import com.leo.blockapp.domain.ports.input.ObserveEnforcedPackages
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

/**
 * Input adapter: tells the [ForegroundEnforcer] which window came to the front and when the device woke up.
 * It reads only the package of TYPE_WINDOW_STATE_CHANGED events and the package of the application windows on screen
 * (to tell split-screen from leaving an app), and decides nothing: all the policy lives in the enforcer, which sees
 * input ports only.
 *
 * Accepted gap of this phase: picture-in-picture windows of a blocked app are counted as visible windows of that app
 * and are not blocked separately.
 *
 * `System::currentTimeMillis` is used as the enforcer's clock because input adapters may not depend on the `Clock`
 * output port, and it matches the production Clock.
 *
 * Field injection is a documented, deliberate exception: the system instantiates accessibility services, so
 * constructor injection is not possible.
 */
@AndroidEntryPoint
class ForegroundAppAccessibilityService : AccessibilityService() {

    @Inject lateinit var evaluateForegroundApp: EvaluateForegroundApp
    @Inject lateinit var observeEnforcedPackages: ObserveEnforcedPackages
    @Inject lateinit var getForegroundPackage: GetForegroundPackage

    private var serviceScope: CoroutineScope? = null
    private var enforcer: ForegroundEnforcer? = null
    private var deviceAwakeReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        stopEnforcing()

        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, failure -> Log.e(TAG, "Unexpected failure in the enforcer", failure) },
        )
        val newEnforcer = ForegroundEnforcer(
            scope = scope,
            evaluateForegroundApp = evaluateForegroundApp,
            observeEnforcedPackages = observeEnforcedPackages,
            getForegroundPackage = getForegroundPackage,
            filter = createSystemForegroundFilter(this),
            blockScreenLauncher = ActivityBlockScreenLauncher(this) { performGlobalAction(GLOBAL_ACTION_HOME) },
            screenState = SystemScreenState(this),
            windowVisibility = windowVisibility(),
            // Wall clock of the adapter: it only stands in for "just opened" when usage has not listed an app yet.
            nowMillis = System::currentTimeMillis,
            logFailure = { message, cause -> Log.w(TAG, message, cause) },
        )
        serviceScope = scope
        enforcer = newEnforcer
        registerDeviceAwakeReceiver(newEnforcer)
        newEnforcer.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        enforcer?.onWindowChanged(packageName)
    }

    override fun onInterrupt() = Unit

    /**
     * The packages of the application windows on screen (split-screen, freeform), whichever app the user touched
     * last. Reads only the package of each window root; needs flagRetrieveInteractiveWindows.
     */
    @Suppress("DEPRECATION")
    private fun visibleApplicationPackages(): Set<String> {
        val needsRecycle = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        val packages = mutableSetOf<String>()
        for (window in windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val root = window.root
                root?.packageName?.toString()?.let { packages += it }
                if (needsRecycle) root?.recycle()
            }
            if (needsRecycle) window.recycle()
        }
        return packages
    }

    private fun windowVisibility() = object : WindowVisibility {
        override fun isPackageVisible(packageName: String): Boolean = packageName in visibleApplicationPackages()

        override fun visibleApplicationPackages(): Set<String> = this@ForegroundAppAccessibilityService.visibleApplicationPackages()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopEnforcing()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopEnforcing()
        super.onDestroy()
    }

    /** ACTION_USER_PRESENT (unlock) and ACTION_SCREEN_ON re-arm the enforcer; it re-checks the keyguard itself. */
    private fun registerDeviceAwakeReceiver(target: ForegroundEnforcer) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                target.onDeviceAwake()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        deviceAwakeReceiver = receiver
    }

    private fun stopEnforcing() {
        deviceAwakeReceiver?.let { unregisterReceiver(it) }
        deviceAwakeReceiver = null
        enforcer?.stop()
        enforcer = null
        serviceScope?.cancel()
        serviceScope = null
    }

    private companion object {
        const val TAG = "ForegroundService"
    }
}

package com.leo.blockapp.adapters.input.service.enforcer

/** Shows the block screen. Implemented by the Android service glue; the enforcer only sees this interface. */
interface BlockScreenLauncher {
    /** Starts the block screen for [packageName]. A failure to start is the implementation's to absorb. */
    fun showBlockScreen(packageName: String)

    /** Sends the user to the home screen: the fallback when the block screen did not appear. */
    fun goHome()
}

/** Whether the user can actually be looking at an app: the screen is on and the keyguard is not showing. */
fun interface ScreenState {
    fun isInteractiveAndUnlocked(): Boolean
}

/** Delays of the enforcer. The defaults are the ones the app runs with; tests use them too. */
data class EnforcerTiming(
    /** Time between two evaluations of the app in the foreground (the detection lag accepted by the spec). */
    val tickMillis: Long = 5_000,
    /** How long to wait for the block screen window before falling back to the home action. */
    val launchConfirmMillis: Long = 1_500,
    val retryInitialMillis: Long = 1_000,
    val retryMaxMillis: Long = 30_000,
)

/** Whether a window of an app is on screen right now, even if it is not the latest app the user touched. */
fun interface WindowVisibility {
    fun isPackageVisible(packageName: String): Boolean
}

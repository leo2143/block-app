package com.leo.blockapp.adapters.input.service.enforcer

/** How the enforcer treats a window that came to the front. */
enum class ForegroundKind {
    /** System UI, keyboards and permission or chooser overlays: they never change which app the user is in. */
    TRANSIENT,

    /** A default launcher: the user went home. */
    LAUNCHER,

    /** This app (its block screen included): it is never evaluated. */
    OWN_APP,

    /** Any other app. Whether it is enforced is decided from the enforced set, not here. */
    APP,
}

/** An activity that declares the HOME category: a launcher, the Recents provider or a boot-time fallback home. */
data class HomeActivity(val packageName: String, val className: String)

/**
 * The packages that count as a launcher: every package with a HOME activity, not only the default one, because the
 * Recents provider (Quickstep) is a HOME-capable package that is not the default when a third-party launcher is set.
 * The resolver (`android`, answered when no default is set) and fallback homes (AOSP `FallbackHome` in Settings) are
 * not launchers.
 */
fun homeLauncherPackages(homeActivities: List<HomeActivity>): Set<String> =
    homeActivities
        .filter { it.packageName != RESOLVER_PACKAGE && !it.className.endsWith(FALLBACK_HOME_SUFFIX) }
        .map { it.packageName }
        .toSet()

private const val RESOLVER_PACKAGE = "android"
private const val FALLBACK_HOME_SUFFIX = "FallbackHome"

/**
 * Classifies a foreground package. The system-dependent sets (enabled keyboards, default launchers, launchable
 * apps) come in as suppliers so this class stays plain Kotlin; they are read on every question because the user can
 * switch keyboard or launcher at any time.
 *
 * A package that is both a launcher and an overlay would be reported as an overlay; no real device does that.
 */
class ForegroundFilter(
    private val ownPackage: String,
    private val inputMethodPackages: () -> Set<String>,
    private val launcherPackages: () -> Set<String>,
    private val hasLauncherIcon: (packageName: String) -> Boolean,
) {

    fun kindOf(packageName: String): ForegroundKind = when {
        packageName in OVERLAY_PACKAGES || packageName in inputMethodPackages() -> ForegroundKind.TRANSIENT
        packageName in launcherPackages() -> ForegroundKind.LAUNCHER
        packageName == ownPackage -> ForegroundKind.OWN_APP
        else -> ForegroundKind.APP
    }

    /** True when the package has a launcher icon, so opening it is the user going to another app. */
    fun isLaunchable(packageName: String): Boolean = hasLauncherIcon(packageName)

    private companion object {
        /** `android` is the chooser/resolver; the others are the system UI and permission or install dialogs. */
        val OVERLAY_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.android.intentresolver",
        )
    }
}

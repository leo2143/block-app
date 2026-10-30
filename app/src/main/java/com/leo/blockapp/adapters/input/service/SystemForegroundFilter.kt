package com.leo.blockapp.adapters.input.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import com.leo.blockapp.adapters.input.service.enforcer.ForegroundFilter
import com.leo.blockapp.adapters.input.service.enforcer.HomeActivity
import com.leo.blockapp.adapters.input.service.enforcer.homeLauncherPackages

/**
 * Android glue: builds the [ForegroundFilter] from what the system says about keyboards, launchers and launcher
 * icons. It needs the queries entries of the manifest (HOME, InputMethod and LAUNCHER intents) to see them.
 *
 * Every package with a HOME activity counts as a launcher, plus the default home: the Recents provider is HOME-capable
 * but not the default when a third-party launcher is set, and opening Recents must end the loop. The resolver and
 * fallback homes are left out (see [homeLauncherPackages]).
 *
 * Accepted limitation: every HOME-capable package (except `android` and `*FallbackHome`) is a launcher, so a
 * secondary, easy-mode or kiosk launcher cannot be blocked.
 */
@Suppress("DEPRECATION")
fun createSystemForegroundFilter(context: Context): ForegroundFilter {
    val packageManager = context.packageManager
    val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    return ForegroundFilter(
        ownPackage = context.packageName,
        inputMethodPackages = { inputMethodManager.enabledInputMethodList.map { it.packageName }.toSet() },
        launcherPackages = {
            val defaultHome = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
            val homeCapable = packageManager.queryIntentActivities(homeIntent, 0).mapNotNull { it.activityInfo }
            homeLauncherPackages((homeCapable + listOfNotNull(defaultHome)).map { HomeActivity(it.packageName, it.name) })
        },
        hasLauncherIcon = { packageManager.getLaunchIntentForPackage(it) != null },
    )
}

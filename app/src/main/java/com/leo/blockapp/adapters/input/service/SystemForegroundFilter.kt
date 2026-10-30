package com.leo.blockapp.adapters.input.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import com.leo.blockapp.adapters.input.service.enforcer.ForegroundFilter

/**
 * Android glue: builds the [ForegroundFilter] from what the system says about keyboards, launchers and launcher
 * icons. It needs the queries entries of the manifest (HOME, InputMethod and LAUNCHER intents) to see them.
 *
 * Only the current default launcher counts as a launcher. `queryIntentActivities(HOME)` would list every package that
 * can act as home (AOSP Settings `FallbackHome`, for one), and those would never be enforced and would end loops.
 */
fun createSystemForegroundFilter(context: Context): ForegroundFilter {
    val packageManager = context.packageManager
    val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    return ForegroundFilter(
        ownPackage = context.packageName,
        inputMethodPackages = { inputMethodManager.enabledInputMethodList.map { it.packageName }.toSet() },
        launcherPackages = {
            val defaultHome = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            // "android" is the resolver/chooser answer when no default launcher is set; it is not a launcher.
            setOfNotNull(defaultHome?.takeIf { it != "android" })
        },
        hasLauncherIcon = { packageManager.getLaunchIntentForPackage(it) != null },
    )
}

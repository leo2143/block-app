package com.leo.blockapp.adapters.output.apps

import android.content.Context
import android.content.Intent
import com.leo.blockapp.domain.model.InstalledApp
import com.leo.blockapp.domain.ports.output.InstalledAppCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Output adapter: lists the apps shown in the launcher (the ones the user can open). */
class PackageManagerAppCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstalledAppCatalog {

    override suspend fun getLaunchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        packageManager.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
    }
}

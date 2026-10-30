package com.leo.blockapp.adapters.output.permissions

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import com.leo.blockapp.adapters.input.service.ForegroundAppAccessibilityService
import com.leo.blockapp.domain.ports.output.SystemPermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AppOpsSystemPermissions @Inject constructor(
    @ApplicationContext private val context: Context,
) : SystemPermissions {

    override fun hasUsageAccess(): Boolean {
        val appOpsManager = context.getSystemService(AppOpsManager::class.java)
        val usageAccessMode = appOpsManager.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return usageAccessMode == AppOpsManager.MODE_ALLOWED
    }

    override fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return EnabledAccessibilityServices.contains(
            enabledServices,
            context.packageName,
            ForegroundAppAccessibilityService::class.java.name,
        )
    }
}

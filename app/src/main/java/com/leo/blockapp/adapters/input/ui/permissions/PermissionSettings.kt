package com.leo.blockapp.adapters.input.ui.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Opens the system screens where the user grants the permissions that make blocks work. */
internal object PermissionSettings {

    fun openAccessibility(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openUsageAccess(context: Context) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    /** App info, where Android 13+ offers "Permitir ajustes restringidos" in the three-dot menu. */
    fun openAppInfo(context: Context) {
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        context.startActivity(appInfo)
    }
}

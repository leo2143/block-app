package com.leo.blockapp.domain.ports.output

/** Output port: special permissions the user grants from the system settings. */
interface SystemPermissions {
    fun hasUsageAccess(): Boolean

    /** True when the user has turned on this app's foreground-detection accessibility service. */
    fun isAccessibilityServiceEnabled(): Boolean
}

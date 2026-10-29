package com.leo.blockapp.domain.ports.output

/** Output port: special permissions the user grants from the system settings. */
interface SystemPermissions {
    fun hasUsageAccess(): Boolean
}

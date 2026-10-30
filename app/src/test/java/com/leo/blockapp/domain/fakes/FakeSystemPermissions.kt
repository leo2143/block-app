package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.ports.output.SystemPermissions

/** Test permissions: both answers are settable. */
class FakeSystemPermissions(
    var usageAccess: Boolean = true,
    var accessibilityServiceEnabled: Boolean = true,
) : SystemPermissions {
    override fun hasUsageAccess() = usageAccess

    override fun isAccessibilityServiceEnabled() = accessibilityServiceEnabled
}

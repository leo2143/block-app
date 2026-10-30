package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.fakes.FakeSystemPermissions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementPermissionUseCasesTest {

    @Test
    fun `the accessibility check follows the system answer each time it is asked`() {
        val permissions = FakeSystemPermissions(accessibilityServiceEnabled = false)
        val isEnabled = IsAccessibilityServiceEnabledUseCase(permissions)

        assertFalse(isEnabled())
        permissions.accessibilityServiceEnabled = true
        assertTrue(isEnabled())
    }

    @Test
    fun `the usage access check follows the system answer each time it is asked`() {
        val permissions = FakeSystemPermissions(usageAccess = false)
        val isGranted = IsUsageAccessGrantedUseCase(permissions)

        assertFalse(isGranted())
        permissions.usageAccess = true
        assertTrue(isGranted())
    }

    @Test
    fun `the two checks are independent`() {
        val permissions = FakeSystemPermissions(usageAccess = true, accessibilityServiceEnabled = false)

        assertTrue(IsUsageAccessGrantedUseCase(permissions)())
        assertFalse(IsAccessibilityServiceEnabledUseCase(permissions)())
    }
}

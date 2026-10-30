package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.ports.input.IsAccessibilityServiceEnabled
import com.leo.blockapp.domain.ports.output.SystemPermissions

class IsAccessibilityServiceEnabledUseCase(
    private val systemPermissions: SystemPermissions,
) : IsAccessibilityServiceEnabled {

    override fun invoke(): Boolean = systemPermissions.isAccessibilityServiceEnabled()
}

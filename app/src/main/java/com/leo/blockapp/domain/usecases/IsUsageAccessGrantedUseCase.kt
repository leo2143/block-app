package com.leo.blockapp.domain.usecases

import com.leo.blockapp.domain.ports.input.IsUsageAccessGranted
import com.leo.blockapp.domain.ports.output.SystemPermissions

class IsUsageAccessGrantedUseCase(
    private val systemPermissions: SystemPermissions,
) : IsUsageAccessGranted {

    override fun invoke(): Boolean = systemPermissions.hasUsageAccess()
}

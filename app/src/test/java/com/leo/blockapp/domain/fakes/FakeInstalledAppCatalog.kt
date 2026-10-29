package com.leo.blockapp.domain.fakes

import com.leo.blockapp.domain.model.InstalledApp
import com.leo.blockapp.domain.ports.output.InstalledAppCatalog

class FakeInstalledAppCatalog(private val launchableApps: List<InstalledApp> = emptyList()) : InstalledAppCatalog {

    var requestCount = 0
        private set

    override suspend fun getLaunchableApps(): List<InstalledApp> {
        requestCount += 1
        return launchableApps
    }
}

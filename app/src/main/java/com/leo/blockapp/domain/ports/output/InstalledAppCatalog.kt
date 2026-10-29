package com.leo.blockapp.domain.ports.output

import com.leo.blockapp.domain.model.InstalledApp

/** Output port: installed apps the user can launch. */
interface InstalledAppCatalog {
    suspend fun getLaunchableApps(): List<InstalledApp>
}

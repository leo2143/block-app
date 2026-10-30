package com.leo.blockapp.adapters.input.service.enforcer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundFilterTest {

    private val ownPackage = "com.leo.blockapp"

    private fun filter(
        inputMethods: () -> Set<String> = { emptySet() },
        launchers: () -> Set<String> = { emptySet() },
        launchablePackages: Set<String> = emptySet(),
    ) = ForegroundFilter(
        ownPackage = ownPackage,
        inputMethodPackages = inputMethods,
        launcherPackages = launchers,
        hasLauncherIcon = { it in launchablePackages },
    )

    @Test
    fun `system overlays are transient`() {
        val filter = filter()

        listOf(
            "com.android.systemui",
            "android",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.android.intentresolver",
        ).forEach { overlayPackage ->
            assertEquals(overlayPackage, ForegroundKind.TRANSIENT, filter.kindOf(overlayPackage))
        }
    }

    @Test
    fun `an enabled keyboard is transient but an app that only looks like one is not`() {
        val filter = filter(inputMethods = { setOf("com.google.android.inputmethod.latin") })

        assertEquals(ForegroundKind.TRANSIENT, filter.kindOf("com.google.android.inputmethod.latin"))
        assertEquals(ForegroundKind.APP, filter.kindOf("com.google.android.inputmethod.latin.fake"))
    }

    @Test
    fun `every default launcher is the launcher kind`() {
        val filter = filter(launchers = { setOf("com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher") })

        assertEquals(ForegroundKind.LAUNCHER, filter.kindOf("com.google.android.apps.nexuslauncher"))
        assertEquals(ForegroundKind.LAUNCHER, filter.kindOf("com.sec.android.app.launcher"))
        assertEquals(ForegroundKind.APP, filter.kindOf("com.instagram.android"))
    }

    @Test
    fun `the own package is neutral`() {
        assertEquals(ForegroundKind.OWN_APP, filter().kindOf(ownPackage))
    }

    @Test
    fun `any other package is an app`() {
        assertEquals(ForegroundKind.APP, filter().kindOf("com.instagram.android"))
    }

    @Test
    fun `the keyboards and launchers are read again on every question`() {
        var inputMethods = emptySet<String>()
        var launchers = emptySet<String>()
        val filter = filter(inputMethods = { inputMethods }, launchers = { launchers })

        assertEquals(ForegroundKind.APP, filter.kindOf("com.swiftkey"))
        assertEquals(ForegroundKind.APP, filter.kindOf("com.new.launcher"))

        inputMethods = setOf("com.swiftkey")
        launchers = setOf("com.new.launcher")

        assertEquals(ForegroundKind.TRANSIENT, filter.kindOf("com.swiftkey"))
        assertEquals(ForegroundKind.LAUNCHER, filter.kindOf("com.new.launcher"))
    }

    @Test
    fun `launchable comes from the supplier`() {
        val filter = filter(launchablePackages = setOf("com.whatsapp"))

        assertTrue(filter.isLaunchable("com.whatsapp"))
        assertFalse(filter.isLaunchable("com.android.packageinstaller.hidden"))
    }
}

package com.leo.blockapp.adapters.output.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnabledAccessibilityServicesTest {

    private val appPackage = "com.leo.blockapp"
    private val serviceClass = "com.leo.blockapp.adapters.input.service.ForegroundAppAccessibilityService"

    private fun isEnabled(setting: String?) = EnabledAccessibilityServices.contains(setting, appPackage, serviceClass)

    @Test
    fun `the full component name is enabled`() {
        assertTrue(isEnabled("$appPackage/$serviceClass"))
    }

    @Test
    fun `the short component name is enabled`() {
        assertTrue(isEnabled("$appPackage/.adapters.input.service.ForegroundAppAccessibilityService"))
    }

    @Test
    fun `the service is found among several enabled services`() {
        val setting = "com.other.app/com.other.app.ReaderService:$appPackage/$serviceClass:com.third/.Svc"

        assertTrue(isEnabled(setting))
    }

    @Test
    fun `a missing or empty setting means disabled`() {
        assertFalse(isEnabled(null))
        assertFalse(isEnabled(""))
        assertFalse(isEnabled(":"))
    }

    @Test
    fun `another service of the same app is not ours`() {
        assertFalse(isEnabled("$appPackage/$appPackage.adapters.input.service.OtherService"))
    }

    @Test
    fun `a service with the same class name in another package is not ours`() {
        assertFalse(isEnabled("com.evil.app/$serviceClass"))
    }

    @Test
    fun `a short name of another package is not ours`() {
        assertFalse(isEnabled("com.evil.app/.adapters.input.service.ForegroundAppAccessibilityService"))
    }

    @Test
    fun `a package that only starts with ours is not ours`() {
        assertFalse(isEnabled("$appPackage.pro/$serviceClass"))
    }

    @Test
    fun `a class that only starts with ours is not ours`() {
        assertFalse(isEnabled("$appPackage/${serviceClass}Impl"))
    }

    @Test
    fun `a malformed entry is ignored`() {
        assertFalse(isEnabled("$appPackage"))
        assertFalse(isEnabled("$appPackage/"))
        assertFalse(isEnabled("/$serviceClass"))
        assertFalse(isEnabled("$appPackage/$serviceClass/extra"))
    }

    @Test
    fun `surrounding whitespace in an entry is tolerated`() {
        assertTrue(isEnabled(" $appPackage/$serviceClass "))
    }
}

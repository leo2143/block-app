package com.leo.blockapp.adapters.input.ui.usage

import com.leo.blockapp.adapters.input.ui.MainDispatcherRule
import com.leo.blockapp.domain.model.TodayUsageResult
import com.leo.blockapp.domain.ports.input.GetTodayUsage
import com.leo.blockapp.domain.ports.input.IsAccessibilityServiceEnabled
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUsageViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var accessibilityEnabled = false
    private val getTodayUsage = object : GetTodayUsage {
        override suspend fun invoke() = TodayUsageResult.MissingUsageAccess
    }

    private fun newViewModel() = AppUsageViewModel(
        getTodayUsage = getTodayUsage,
        isAccessibilityServiceEnabled = IsAccessibilityServiceEnabled { accessibilityEnabled },
    )

    @Test
    fun `the card state is known from the start`() {
        accessibilityEnabled = false

        assertFalse(newViewModel().accessibilityEnabled.value)
    }

    @Test
    fun `returning from settings rechecks the accessibility service`() = runTest {
        accessibilityEnabled = false
        val vm = newViewModel()

        accessibilityEnabled = true
        vm.refreshUsage()
        advanceUntilIdle()

        assertTrue(vm.accessibilityEnabled.value)
    }

    @Test
    fun `turning the service off is noticed on the next recheck`() = runTest {
        accessibilityEnabled = true
        val vm = newViewModel()
        assertTrue(vm.accessibilityEnabled.value)

        accessibilityEnabled = false
        vm.refreshUsage()
        advanceUntilIdle()

        assertFalse(vm.accessibilityEnabled.value)
    }

    @Test
    fun `the usage state still follows the usage port`() = runTest {
        val vm = newViewModel()

        vm.refreshUsage()
        advanceUntilIdle()

        assertEquals(AppUsageUiState.MissingUsageAccess, vm.uiState.value)
    }
}

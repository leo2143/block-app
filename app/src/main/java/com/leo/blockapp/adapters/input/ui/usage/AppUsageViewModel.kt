package com.leo.blockapp.adapters.input.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leo.blockapp.domain.model.AppUsage
import com.leo.blockapp.domain.model.TodayUsageResult
import com.leo.blockapp.domain.ports.input.GetTodayUsage
import com.leo.blockapp.domain.ports.input.IsAccessibilityServiceEnabled
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AppUsageUiState {
    data object Loading : AppUsageUiState
    data object MissingUsageAccess : AppUsageUiState
    data class Loaded(val appUsages: List<AppUsage>) : AppUsageUiState
}

/** Input adapter: only knows input ports, never Android services or output adapters. */
@HiltViewModel
class AppUsageViewModel @Inject constructor(
    private val getTodayUsage: GetTodayUsage,
    private val isAccessibilityServiceEnabled: IsAccessibilityServiceEnabled,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AppUsageUiState>(AppUsageUiState.Loading)
    val uiState: StateFlow<AppUsageUiState> = _uiState.asStateFlow()

    private val _accessibilityEnabled = MutableStateFlow(isAccessibilityServiceEnabled())

    /** Whether the blocking service is on; the screen shows the activation card while it is not. */
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    /** Called on every resume, e.g. when returning from Settings: rechecks the service and reloads the usage. */
    fun refreshUsage() {
        _accessibilityEnabled.value = isAccessibilityServiceEnabled()
        viewModelScope.launch {
            _uiState.value = when (val todayUsage = getTodayUsage()) {
                TodayUsageResult.MissingUsageAccess -> AppUsageUiState.MissingUsageAccess
                is TodayUsageResult.Available -> AppUsageUiState.Loaded(todayUsage.appUsages)
            }
        }
    }
}

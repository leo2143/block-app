package com.leo.blockapp.adapters.input.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leo.blockapp.domain.model.AppUsage
import com.leo.blockapp.domain.model.TodayUsageResult
import com.leo.blockapp.domain.ports.input.GetTodayUsage
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

/** Input adapter: only knows the GetTodayUsage port, never Android services or output adapters. */
@HiltViewModel
class AppUsageViewModel @Inject constructor(
    private val getTodayUsage: GetTodayUsage,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AppUsageUiState>(AppUsageUiState.Loading)
    val uiState: StateFlow<AppUsageUiState> = _uiState.asStateFlow()

    fun refreshUsage() {
        viewModelScope.launch {
            _uiState.value = when (val todayUsage = getTodayUsage()) {
                TodayUsageResult.MissingUsageAccess -> AppUsageUiState.MissingUsageAccess
                is TodayUsageResult.Available -> AppUsageUiState.Loaded(todayUsage.appUsages)
            }
        }
    }
}

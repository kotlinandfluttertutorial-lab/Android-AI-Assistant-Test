/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag
 * File       : BenchmarkViewModel.kt
 * Purpose    : Drives BenchmarkScreen — runs BenchmarkOnDeviceUseCase and
 *              emits BenchmarkUiState transitions.
 *
 * Architecture Layer : Feature (feature-on-device-rag) — MVVM ViewModel.
 *
 * Requirements: 32.3, 32.4, 32.5
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.OnDeviceBenchmarkResult
import com.aiassistant.domain.usecase.ondevicerag.BenchmarkOnDeviceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** All possible states for BenchmarkScreen. */
sealed class BenchmarkUiState {
    data object Idle : BenchmarkUiState()
    data class Running(val iteration: Int = 0) : BenchmarkUiState()
    data class Done(val result: OnDeviceBenchmarkResult) : BenchmarkUiState()
    data class Error(val message: String) : BenchmarkUiState()
}

@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    private val benchmarkUseCase: BenchmarkOnDeviceUseCase,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BenchmarkUiState>(BenchmarkUiState.Idle)
    val uiState: StateFlow<BenchmarkUiState> = _uiState.asStateFlow()

    fun runBenchmark() {
        viewModelScope.launch(dispatchers.io) {
            _uiState.value = BenchmarkUiState.Running(iteration = 0)
            when (val result = benchmarkUseCase()) {
                is ApiResult.Success -> _uiState.value = BenchmarkUiState.Done(result.data)
                is ApiResult.Error ->
                    _uiState.value = BenchmarkUiState.Error(result.error.message)
                else -> _uiState.value = BenchmarkUiState.Error("Benchmark failed unexpectedly.")
            }
        }
    }
}

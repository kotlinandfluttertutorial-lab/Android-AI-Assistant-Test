/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag
 * File       : ManageModelsViewModel.kt
 * Purpose    : Drives ManageModelsScreen — lists, downloads, deletes models
 *              via ManageOnDeviceModelsUseCase; tracks per-model download
 *              progress and Battery Saver state.
 *
 * Architecture Layer : Feature (feature-on-device-rag) — MVVM ViewModel.
 *
 * Requirements: 32.3, 32.4, 32.5, 37.3, 37.4, 37.5, 37.8, 37.10
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.OnDeviceModelInfo
import com.aiassistant.domain.usecase.ondevicerag.ManageOnDeviceModelsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Download progress state per model. */
sealed class DownloadState {
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val percent: Int) : DownloadState()
    data object Verifying : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/** UI state for ManageModelsScreen. */
data class ManageModelsUiState(
    val isLoading: Boolean = true,
    val models: List<OnDeviceModelInfo> = emptyList(),
    val downloadProgress: Map<String, DownloadState> = emptyMap(),
    val batterySaverActive: Boolean = false,
    val updateAvailableModelName: String? = null
)

@HiltViewModel
class ManageModelsViewModel @Inject constructor(
    private val manageModelsUseCase: ManageOnDeviceModelsUseCase,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManageModelsUiState())
    val uiState: StateFlow<ManageModelsUiState> = _uiState.asStateFlow()

    init {
        loadModels()
    }

    fun loadModels() {
        viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = manageModelsUseCase.listModels()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, models = result.data)
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false)
                }
                else -> _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun downloadModel(model: OnDeviceModelInfo) {
        viewModelScope.launch(dispatchers.io) {
            manageModelsUseCase.downloadModel(model)
                .catch { e ->
                    _uiState.update { state ->
                        state.copy(
                            downloadProgress = state.downloadProgress +
                                (model.name to DownloadState.Error(e.message ?: "Unknown error"))
                        )
                    }
                }
                .collect { result ->
                    when (result) {
                        is ApiResult.Success -> {
                            val progress = result.data
                            val state = if (progress.percentComplete >= 100) {
                                DownloadState.Verifying
                            } else {
                                DownloadState.Downloading(
                                    bytesDownloaded = progress.bytesDownloaded,
                                    totalBytes = progress.totalBytes,
                                    percent = progress.percentComplete
                                )
                            }
                            _uiState.update { s ->
                                s.copy(downloadProgress = s.downloadProgress + (model.name to state))
                            }
                            // Refresh list on completion
                            if (progress.percentComplete >= 100) loadModels()
                        }
                        is ApiResult.Error -> _uiState.update { s ->
                            s.copy(
                                downloadProgress = s.downloadProgress +
                                    (model.name to DownloadState.Error(result.error.message))
                            )
                        }
                        else -> Unit
                    }
                }
        }
    }

    fun deleteModel(model: OnDeviceModelInfo) {
        viewModelScope.launch(dispatchers.io) {
            manageModelsUseCase.deleteModel(model)
            loadModels()
        }
    }
}

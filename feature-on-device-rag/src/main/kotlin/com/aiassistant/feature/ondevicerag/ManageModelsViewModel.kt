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

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
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

private const val TAG = "ManageModelsVM"

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
            Log.d(TAG, "loadModels started")
            _uiState.update { it.copy(isLoading = true) }
            when (val result = manageModelsUseCase.listModels()) {
                is ApiResult.Success -> {
                    Log.d(TAG, "loadModels success, loaded ${result.data.size} models")
                    _uiState.update {
                        it.copy(isLoading = false, models = result.data)
                    }
                }
                is ApiResult.Error -> {
                    Log.e(TAG, "loadModels error: ${result.error.message}")
                    _uiState.update {
                        it.copy(isLoading = false)
                    }
                }
                else -> {
                    Log.d(TAG, "loadModels other result")
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun downloadModel(model: OnDeviceModelInfo) {
        Log.d(TAG, "downloadModel requested for model: ${model.name}")
        viewModelScope.launch(dispatchers.io) {
            manageModelsUseCase.downloadModel(model)
                .catch { e ->
                    Log.e(TAG, "downloadModel exception for ${model.name}: ${e.message}", e)
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
                            Log.d(TAG, "downloadModel progress for ${model.name}: ${progress.percentComplete}% (${progress.bytesDownloaded}/${progress.totalBytes} bytes)")
                            if (progress.percentComplete >= 100) {
                                Log.d(TAG, "downloadModel reached 100% for ${model.name}, starting verification...")
                                _uiState.update { s ->
                                    s.copy(downloadProgress = s.downloadProgress + (model.name to DownloadState.Verifying))
                                }
                                val verifyResult = manageModelsUseCase.verifyModel(model)
                                Log.d(TAG, "verifyModel result for ${model.name}: $verifyResult")
                                val finalState = when (verifyResult) {
                                    is ApiResult.Success -> {
                                        if (verifyResult.data) {
                                            Log.d(TAG, "verifyModel succeeded for ${model.name}")
                                            null
                                        } else {
                                            Log.e(TAG, "verifyModel failed (data=false) for ${model.name}")
                                            DownloadState.Error("Checksum verification failed")
                                        }
                                    }
                                    is ApiResult.Error -> {
                                        Log.e(TAG, "verifyModel error for ${model.name}: ${verifyResult.error.message}")
                                        DownloadState.Error(verifyResult.error.message)
                                    }
                                    else -> {
                                        Log.e(TAG, "verifyModel unknown result for ${model.name}")
                                        DownloadState.Error("Verification failed")
                                    }
                                }
                                _uiState.update { s ->
                                    val newProgress = if (finalState == null) {
                                        s.downloadProgress - model.name
                                    } else {
                                        s.downloadProgress + (model.name to finalState)
                                    }
                                    s.copy(downloadProgress = newProgress)
                                }
                                loadModels()
                            } else {
                                val state = DownloadState.Downloading(
                                    bytesDownloaded = progress.bytesDownloaded,
                                    totalBytes = progress.totalBytes,
                                    percent = progress.percentComplete
                                )
                                _uiState.update { s ->
                                    s.copy(downloadProgress = s.downloadProgress + (model.name to state))
                                }
                            }
                        }
                        is ApiResult.Error -> {
                            Log.e(TAG, "downloadModel error result for ${model.name}: ${result.error.message}")
                            _uiState.update { s ->
                                s.copy(
                                    downloadProgress = s.downloadProgress +
                                        (model.name to DownloadState.Error(result.error.message))
                                )
                            }
                        }
                        else -> Unit
                    }
                }
        }
    }

    fun deleteModel(model: OnDeviceModelInfo) {
        Log.d(TAG, "deleteModel requested for ${model.name}")
        viewModelScope.launch(dispatchers.io) {
            manageModelsUseCase.deleteModel(model)
            loadModels()
        }
    }
}

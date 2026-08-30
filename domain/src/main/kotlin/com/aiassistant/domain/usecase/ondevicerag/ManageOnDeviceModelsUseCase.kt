/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ManageOnDeviceModelsUseCase.kt
 * Purpose    : Provides the four model-file lifecycle operations used by
 *              ManageModelsScreen: list, download, verify, delete.
 *
 * Architecture Layer : Domain — pure Kotlin use case (four operations on
 *                      one class to keep the model-file concern cohesive).
 *
 * Requirements: 37.3, 37.4, 37.5, 37.8, 37.10
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.OnDeviceModelInfo
import com.aiassistant.domain.repository.DownloadProgress
import com.aiassistant.domain.repository.ModelFileRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Manages the lifecycle of on-device AI model files (Gemma + embedding model).
 *
 * @param modelFileRepository Wraps WorkManager download jobs and file-system operations.
 */
class ManageOnDeviceModelsUseCase @Inject constructor(private val modelFileRepository: ModelFileRepository) {

    /**
     * Returns the list of models currently stored on the device.
     *
     * @return [ApiResult.Success] with a list of [OnDeviceModelInfo]; may be empty.
     */
    suspend fun listModels(): ApiResult<List<OnDeviceModelInfo>> = modelFileRepository.listModels()

    /**
     * Starts a WorkManager job to download [model] and returns a live download
     * progress [Flow].
     *
     * The download uses unmetered networks by default.  Set [allowMetered] = true
     * only after the user has confirmed a mobile-data warning dialog in the UI.
     *
     * Resume-from-byte is supported: interrupted downloads continue from the last
     * received byte (Requirement 37.5).
     *
     * @param model        The model entry from the manifest to download.
     * @param allowMetered Whether mobile data is permitted.
     * @return Cold [Flow] of [ApiResult<DownloadProgress>]:
     *         - [ApiResult.Loading] with progress while in progress.
     *         - [ApiResult.Success] when download + checksum verification complete.
     *         - [ApiResult.Error] on failure.
     */
    fun downloadModel(model: OnDeviceModelInfo, allowMetered: Boolean = false): Flow<ApiResult<DownloadProgress>> =
        modelFileRepository.downloadModel(model, allowMetered)

    /**
     * Verifies the SHA-256 checksum of an already-downloaded [model] file.
     *
     * Returns [ApiResult.Success]`(true)` when valid, [ApiResult.Error] when
     * the file is missing or corrupted.  Used by [OnDeviceEmbeddingModel] and
     * [OnDeviceInferenceEngine] to gate model loading.
     */
    suspend fun verifyModel(model: OnDeviceModelInfo): ApiResult<Boolean> = modelFileRepository.verifyModel(model)

    /**
     * Deletes the model file from internal storage.
     *
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun deleteModel(model: OnDeviceModelInfo): ApiResult<Unit> = modelFileRepository.deleteModel(model)
}

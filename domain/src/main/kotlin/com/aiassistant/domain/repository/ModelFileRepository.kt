/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ModelFileRepository.kt
 * Purpose    : Domain contract for managing on-device model files (Gemma and
 *              the MiniLM embedding model).  Implemented by ModelFileRepositoryImpl
 *              in the data module using WorkManager downloads and file-system storage.
 *
 * Architecture Layer : Domain — interface only, zero Android dependencies.
 *
 * Dependencies       : core-common (ApiResult), domain model (OnDeviceModelInfo)
 *
 * Design Decision    : downloadModel() returns Flow<ApiResult<DownloadProgress>>
 *                      so the feature screen can show a live percentage + ETA
 *                      without coupling to WorkManager directly.
 *                      verifyModel() is a separate suspend function so callers
 *                      can re-verify an existing file without re-downloading it.
 *
 * Requirements: 33.5, 33.6, 37.3, 37.4, 37.5, 37.9, 37.10
 * ============================================================
 */
package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.OnDeviceModelInfo
import kotlinx.coroutines.flow.Flow

/**
 * Progress snapshot emitted during a model file download.
 *
 * @param bytesDownloaded  Bytes received so far.
 * @param totalBytes       Total file size in bytes; -1 if unknown.
 * @param percentComplete  0–100 integer percentage; -1 if indeterminate.
 */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percentComplete: Int,
)

/**
 * Persistence and download contract for on-device AI model files.
 */
interface ModelFileRepository {

    /**
     * Returns the list of models currently stored on the device (verified + unverified).
     */
    suspend fun listModels(): ApiResult<List<OnDeviceModelInfo>>

    /**
     * Starts a WorkManager download job for [model] and returns a cold [Flow] that
     * emits [DownloadProgress] updates until the file is fully downloaded and verified.
     *
     * The download uses [NetworkType.UNMETERED] by default.  If [allowMetered] is true
     * the constraint is relaxed to [NetworkType.CONNECTED].
     *
     * A mobile-data warning dialog should be shown by the feature screen before
     * setting [allowMetered] = true.
     *
     * Resume-from-byte is supported: if a previous download was interrupted the job
     * continues from the last received byte (Requirement 37.5).
     *
     * @param model        The model to download (from the manifest).
     * @param allowMetered Whether metered (mobile data) networks are permitted.
     * @return Cold [Flow] of [ApiResult]:
     *         - [ApiResult.Loading] wrapping [DownloadProgress] while in progress.
     *         - [ApiResult.Success] with the completed [OnDeviceModelInfo] on finish.
     *         - [ApiResult.Error] if the download or checksum verification fails.
     */
    fun downloadModel(
        model: OnDeviceModelInfo,
        allowMetered: Boolean = false,
    ): Flow<ApiResult<DownloadProgress>>

    /**
     * Verifies the SHA-256 checksum of a previously downloaded model file.
     *
     * @param model Model to verify.
     * @return [ApiResult.Success] with [true] if the checksum matches; [ApiResult.Error]
     *         if the file is missing or corrupted.
     */
    suspend fun verifyModel(model: OnDeviceModelInfo): ApiResult<Boolean>

    /**
     * Deletes the model file from internal storage.
     *
     * @param model Model to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun deleteModel(model: OnDeviceModelInfo): ApiResult<Unit>

    /**
     * Returns the absolute path to the model file on the device's internal storage,
     * or null if the file has not been downloaded.
     */
    suspend fun getModelPath(model: OnDeviceModelInfo): String?
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : OnDeviceDocumentRepository.kt
 * Purpose    : Domain contract for on-device document persistence operations.
 *              Implemented by OnDeviceDocumentRepositoryImpl (data module) which
 *              wraps OnDeviceDocumentDao and OnDeviceChunkDao from core-database.
 *
 * Architecture Layer : Domain — interface only, zero Android dependencies.
 *
 * Dependencies       : core-common (ApiResult), domain model
 *
 * Design Decision    : This repository is strictly offline-first with no remote
 *                      sync — on-device RAG data never leaves the device.
 *                      getDocuments() returns a Flow<List<OnDeviceDocument>> (not
 *                      Flow<ApiResult<…>>) because the data is always available
 *                      locally and there is no network call to fail.
 *
 * Requirements: 33.5, 33.6, 33.7, 33.10
 * ============================================================
 */
package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.OnDeviceDocument
import com.aiassistant.domain.model.OnDeviceIngestionStatus
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for documents in the on-device RAG pipeline.
 *
 * All operations are local — no network calls are ever made from this repository.
 */
interface OnDeviceDocumentRepository {

    /**
     * Returns a live [Flow] of all on-device documents belonging to [userId],
     * ordered newest-first. Emits a new list on every Room change.
     */
    fun getDocuments(userId: String): Flow<List<OnDeviceDocument>>

    /**
     * Persists a new document record in PENDING state before ingestion starts.
     *
     * @return [ApiResult.Success] with the saved [OnDeviceDocument].
     */
    suspend fun saveDocument(document: OnDeviceDocument): ApiResult<OnDeviceDocument>

    /**
     * Updates the ingestion status, failure stage, and chunk count for a document.
     *
     * @param id           Target document id.
     * @param status       New [OnDeviceIngestionStatus].
     * @param failureStage Pipeline stage that failed ("extraction"|"chunking"|"embedding"),
     *                     or null when [status] is not FAILED.
     * @param totalChunks  Final chunk count on READY; 0 otherwise.
     */
    suspend fun updateStatus(
        id: String,
        status: OnDeviceIngestionStatus,
        failureStage: String?,
        totalChunks: Int
    ): ApiResult<Unit>

    /**
     * Hard-deletes a document and all its chunks (via CASCADE FK).
     * The delete must complete within 10 seconds (Requirement 33.10).
     *
     * @param id     Document to remove.
     * @param userId Scoping guard — prevents cross-user deletion.
     */
    suspend fun deleteDocument(id: String, userId: String): ApiResult<Unit>
}

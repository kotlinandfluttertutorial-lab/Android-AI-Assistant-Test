/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : OnDeviceDocumentRepositoryImpl.kt
 * Purpose    : Implements OnDeviceDocumentRepository using Room DAOs from
 *              core-database.  Strictly offline — no network calls ever made.
 *
 * Architecture Layer : Data — repository implementation.
 *                      Wraps OnDeviceDocumentDao and OnDeviceChunkDao.
 *                      Bound to OnDeviceDocumentRepository via Hilt in
 *                      OnDeviceRagModule.
 *
 * Dependencies       : core-database (OnDeviceDocumentDao, OnDeviceChunkDao),
 *                      core-common (DispatcherProvider, ApiResult),
 *                      domain model + repository interface.
 *
 * Design Decision    : getDocuments() emits directly from Room's Flow — no
 *                      remote sync, no ConnectivityObserver check.  On-device
 *                      RAG data is local-only by design (Requirement 33.5).
 *                      Entity↔domain mapping is done inline (no separate mapper
 *                      file) to keep the on-device RAG data path self-contained.
 *
 * Requirements: 33.5, 33.6, 33.7, 33.10
 * ============================================================
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.database.dao.OnDeviceDocumentDao
import com.aiassistant.core.database.entity.OnDeviceDocumentEntity
import com.aiassistant.domain.model.OnDeviceDocument
import com.aiassistant.domain.model.OnDeviceIngestionStatus
import com.aiassistant.domain.repository.OnDeviceDocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceDocumentRepositoryImpl @Inject constructor(
    private val documentDao: OnDeviceDocumentDao,
    private val dispatchers: DispatcherProvider,
) : OnDeviceDocumentRepository {

    // ── OnDeviceDocumentRepository ────────────────────────────────────────

    override fun getDocuments(userId: String): Flow<List<OnDeviceDocument>> =
        documentDao.getDocuments(userId).map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun saveDocument(document: OnDeviceDocument): ApiResult<OnDeviceDocument> =
        withContext(dispatchers.io) {
            try {
                documentDao.insert(document.toEntity())
                ApiResult.Success(document)
            } catch (e: Exception) {
                ApiResult.Error(
                    com.aiassistant.core.common.DomainError.ServerError(
                        message = "Failed to save document: ${e.message}", code = 500
                    )
                )
            }
        }

    override suspend fun updateStatus(
        id: String,
        status: OnDeviceIngestionStatus,
        failureStage: String?,
        totalChunks: Int,
    ): ApiResult<Unit> = withContext(dispatchers.io) {
        try {
            documentDao.updateStatus(id, status.value, failureStage, totalChunks)
            ApiResult.Success(Unit)
        } catch (e: Exception) {
            ApiResult.Error(
                com.aiassistant.core.common.DomainError.ServerError(
                    message = "Failed to update document status: ${e.message}", code = 500
                )
            )
        }
    }

    override suspend fun deleteDocument(id: String, userId: String): ApiResult<Unit> =
        withContext(dispatchers.io) {
            try {
                documentDao.delete(id, userId)
                ApiResult.Success(Unit)
            } catch (e: Exception) {
                ApiResult.Error(
                    com.aiassistant.core.common.DomainError.ServerError(
                        message = "Failed to delete document: ${e.message}", code = 500
                    )
                )
            }
        }

    // ── Entity ↔ Domain mappers ───────────────────────────────────────────

    private fun OnDeviceDocumentEntity.toDomain() = OnDeviceDocument(
        id = id,
        userId = userId,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        totalChunks = totalChunks,
        ingestionStatus = OnDeviceIngestionStatus.fromValue(ingestionStatus),
        failureStage = failureStage,
        createdAt = createdAt,
    )

    private fun OnDeviceDocument.toEntity() = OnDeviceDocumentEntity(
        id = id,
        userId = userId,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        totalChunks = totalChunks,
        ingestionStatus = ingestionStatus.value,
        failureStage = failureStage,
        createdAt = createdAt,
    )
}

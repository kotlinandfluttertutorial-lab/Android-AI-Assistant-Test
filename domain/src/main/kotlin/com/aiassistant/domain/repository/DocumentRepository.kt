/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DocumentRepository.kt
 * Purpose    : Domain contract defining data access operations for Document entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * DocumentRepository.kt
 *
 * Purpose: Domain-layer repository interface for RAG document operations.
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain model (Document, IngestionStatus)
 *
 * Requirements: 4.1, 4.6, 4.10, 19.2
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.IngestionStatus
import kotlinx.coroutines.flow.Flow

/**
 * Contract for document (RAG) operations between the domain and data layers.
 *
 * The data module provides a concrete implementation that:
 * - Uploads files via multipart POST to `/documents`
 * - Polls `GET /jobs/{id}` to track [IngestionStatus] transitions
 * - Caches document metadata locally (but not file contents)
 */
interface DocumentRepository {

    /**
     * Returns a [Flow] of all documents belonging to the authenticated user, sorted by
     * upload date descending.
     *
     * @return Cold [Flow] emitting [ApiResult.Success] with the full document list.
     */
    fun getDocuments(): Flow<ApiResult<List<Document>>>

    /**
     * Uploads a document to the backend and initiates RAG ingestion.
     *
     * The data layer performs a multipart POST to `/documents`. A [Document] with
     * [IngestionStatus.PENDING] is returned immediately. The caller should poll
     * [getIngestionStatus] until the status reaches [IngestionStatus.READY] or
     * [IngestionStatus.FAILED] (Requirement 4.1).
     *
     * @param fileUri   Content URI pointing to the file to upload.
     * @param fileName  Original file name as displayed in the UI.
     * @param mimeType  MIME type of the file being uploaded.
     * @return [ApiResult.Success] with the created [Document] (in PENDING state) on success.
     */
    suspend fun uploadDocument(fileUri: String, fileName: String, mimeType: String): ApiResult<Document>

    /**
     * Polls the backend for the current ingestion status of a document.
     *
     * Calls `GET /jobs/{jobId}` and maps the response to an [IngestionStatus] value.
     * The data layer also updates the local Room cache with the new status.
     *
     * @param documentId The unique identifier of the document to check.
     * @return [ApiResult.Success] with the current [IngestionStatus] on success.
     */
    suspend fun getIngestionStatus(documentId: String): ApiResult<IngestionStatus>

    /**
     * Sends a natural language query against the RAG index for a specific document and
     * returns an AI-generated response with cited source references (Requirement 4.6).
     *
     * @param documentId The unique identifier of the document to query against.
     * @param query      The user's natural language question.
     * @return [ApiResult.Success] with the response text (including inline citations) on success.
     */
    suspend fun queryDocument(documentId: String, query: String): ApiResult<String>

    /**
     * Deletes a document from both the local cache and the backend, including its
     * ChromaDB embeddings (Requirement 4.10).
     *
     * @param documentId The unique identifier of the document to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun deleteDocument(documentId: String): ApiResult<Unit>
}

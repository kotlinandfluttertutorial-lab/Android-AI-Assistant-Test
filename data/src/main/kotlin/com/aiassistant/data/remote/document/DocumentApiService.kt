/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : DocumentApiService.kt
 * Purpose    : DocumentApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : DocumentApiService.kt
 * Purpose    : DocumentApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
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
 * DocumentApiService.kt — data module
 *
 * Purpose: Retrofit service interface for all `/documents/...` and `/jobs/...` REST endpoints.
 *          Consumed exclusively by [DocumentRemoteDataSource].
 *
 * Architecture: data module — remote data source layer.
 * Dependencies: Retrofit, kotlinx.serialization, OkHttp (MultipartBody for upload)
 *
 * Requirements: 4.1, 4.6, 4.10
 */
package com.aiassistant.data.remote.document

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

// ─── Request / Response models ────────────────────────────────────────────────

/**
 * Response returned by `GET /documents`.
 */
@Serializable
data class DocumentListResponseDto(
    @SerialName("documents") val documents: List<DocumentDto>,
    @SerialName("total") val total: Int
)

/**
 * Representation of a single document in list responses.
 */
@Serializable
data class DocumentDto(
    @SerialName("id") val id: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    /** "pending" | "processing" | "ready" | "failed" */
    @SerialName("ingestion_status") val ingestionStatus: String,
    @SerialName("page_count") val pageCount: Int? = null,
    /** ISO-8601 string from backend */
    @SerialName("created_at") val createdAt: String
)

/**
 * Response body for `POST /documents/upload`.
 */
@Serializable
data class DocumentUploadResponseDto(
    @SerialName("document_id") val documentId: String,
    @SerialName("job_id") val jobId: String,
    @SerialName("status") val status: String
)

/**
 * Response returned by `GET /jobs/{id}` for ingestion status polling.
 */
@Serializable
data class JobStatusDto(
    @SerialName("job_id") val jobId: String,
    @SerialName("document_id") val documentId: String? = null,
    /** "pending" | "processing" | "ready" | "failed" */
    @SerialName("status") val status: String,
    @SerialName("error_message") val errorMessage: String? = null
)

/**
 * Request body for `POST /documents/query`.
 */
@Serializable
data class DocumentQueryRequest(
    @SerialName("query") val query: String,
    @SerialName("document_ids") val documentIds: List<String>? = null,
    @SerialName("top_k") val topK: Int = 5
)

/**
 * Response returned by `POST /documents/query`.
 */
@Serializable
data class DocumentQueryResponse(
    @SerialName("answer") val answer: String,
    @SerialName("citations") val citations: List<CitationDto>,
    @SerialName("context_used") val contextUsed: String
)

/**
 * Citation metadata for a retrieved chunk.
 */
@Serializable
data class CitationDto(
    @SerialName("document_name") val documentName: String,
    @SerialName("page_number") val pageNumber: Int,
    @SerialName("chunk_index") val chunkIndex: Int
)

// ─── Retrofit service interface ───────────────────────────────────────────────

/**
 * Retrofit service for document and RAG job endpoints.
 *
 * Instantiated by [com.aiassistant.data.di.DocumentDataModule] and injected into
 * [DocumentRemoteDataSource].
 */
interface DocumentApiService {

    /**
     * Returns all documents belonging to the authenticated user (sorted by createdAt DESC).
     */
    @GET("documents")
    suspend fun getDocuments(): DocumentListResponseDto

    /**
     * Uploads a document as a multipart form upload.
     *
     * Returns a [DocumentUploadResponseDto] with status "pending" and a
     * jobId to use for polling (Requirement 4.1).
     *
     * @param file The file part (binary content + filename + content-type).
     */
    @Multipart
    @POST("documents/upload")
    suspend fun uploadDocument(@Part file: MultipartBody.Part): DocumentUploadResponseDto

    /**
     * Polls the ingestion job status for [jobId] (Requirement 4.1).
     *
     * @param jobId The Celery job identifier returned in [DocumentUploadResponseDto.jobId].
     */
    @GET("jobs/{jobId}")
    suspend fun getJobStatus(@Path("jobId") jobId: String): JobStatusDto

    /**
     * Sends a natural language query against the document's RAG index (Requirement 4.6).
     *
     * @param body The query request body.
     */
    @POST("documents/query")
    suspend fun queryDocuments(@Body body: DocumentQueryRequest): DocumentQueryResponse

    /**
     * Deletes a document and all its associated embeddings (Requirement 4.10).
     *
     * @param documentId The document to delete.
     */
    @DELETE("documents/{documentId}")
    suspend fun deleteDocument(@Path("documentId") documentId: String): Unit
}

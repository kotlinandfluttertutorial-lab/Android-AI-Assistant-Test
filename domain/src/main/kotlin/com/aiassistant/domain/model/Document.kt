/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : Document.kt
 * Purpose    : Document — domain module component
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Class
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
 * Module     : domain
 * File       : Document.kt
 * Purpose    : Document — domain module component
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Class
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
 * Document.kt
 *
 * Purpose: Domain entity representing an uploaded document in the RAG pipeline.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 4.1, 4.6, 19.2
 */

package com.aiassistant.domain.model

/**
 * The lifecycle state of a document as it moves through the RAG ingestion pipeline.
 *
 * State transitions: PENDING â†’ PROCESSING â†’ READY
 *                                         â†˜ FAILED
 */
enum class IngestionStatus(val value: String) {
    /** Document has been uploaded and is awaiting processing. */
    PENDING("pending"),

    /** Celery worker is currently extracting text and generating embeddings. */
    PROCESSING("processing"),

    /** Ingestion complete; document is available for semantic search queries. */
    READY("ready"),

    /** Ingestion failed; user may retry the upload. */
    FAILED("failed");

    companion object {
        fun fromValue(value: String): IngestionStatus = entries.firstOrNull { it.value == value } ?: PENDING
    }
}

/**
 * Represents an uploaded document that has been or is being processed by the RAG pipeline.
 *
 * THE AI_Assistant SHALL display an ingestion status badge per document (Requirement 4.1).
 * Once [ingestionStatus] is [IngestionStatus.READY], the document is available for
 * [com.aiassistant.domain.usecase.document.QueryDocumentUseCase] (Requirement 4.6).
 *
 * @param id               Unique identifier for the document.
 * @param userId           Identifier of the owning user.
 * @param fileName         The original file name as uploaded by the user.
 * @param mimeType         MIME type of the document (e.g. "application/pdf").
 * @param sizeBytes        File size in bytes.
 * @param ingestionStatus  Current RAG pipeline processing state.
 * @param jobId            Optional Celery job identifier for status polling.
 * @param pageCount        Optional page count (available after successful ingestion).
 * @param createdAt        Epoch milliseconds when the document was uploaded.
 */
data class Document(
    val id: String,
    val userId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val ingestionStatus: IngestionStatus = IngestionStatus.PENDING,
    val jobId: String? = null,
    val pageCount: Int? = null,
    val createdAt: Long
)

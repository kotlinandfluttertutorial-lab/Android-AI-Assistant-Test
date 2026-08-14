/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : UploadDocumentUseCase.kt
 * Purpose    : Encapsulates the 'UploadDocument' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * File       : UploadDocumentUseCase.kt
 * Purpose    : Encapsulates the 'UploadDocument' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * UploadDocumentUseCase.kt
 *
 * Purpose: Validates a document file's size and format, then delegates the upload to
 *          DocumentRepository to initiate RAG ingestion.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), DocumentRepository, Document
 *
 * Requirements: 4.1
 *
 * Design decisions:
 * - Pre-condition validation (file size â‰¤ 50 MB, allowed MIME types) is enforced in the
 *   domain layer so invalid uploads never reach the network layer.
 * - Accepted MIME types map to PDF, DOCX, TXT, and Markdown per Requirement 4.1.
 */

package com.aiassistant.domain.usecase.document

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.repository.DocumentRepository
import javax.inject.Inject

/**
 * Use case for uploading a document to the RAG pipeline.
 *
 * THE RAG_Pipeline SHALL accept document uploads in PDF, DOCX, TXT, and Markdown formats
 * with a maximum file size of 50 MB per document (Requirement 4.1).
 *
 * @param documentRepository Repository providing the document upload operation.
 */
class UploadDocumentUseCase @Inject constructor(private val documentRepository: DocumentRepository) {

    /**
     * Validates the file and initiates the upload.
     *
     * @param fileUri   Content URI pointing to the file to upload.
     * @param fileName  Original file name as displayed in the UI.
     * @param mimeType  MIME type of the file being uploaded.
     * @param sizeBytes File size in bytes; must be â‰¤ [MAX_FILE_SIZE_BYTES].
     * @return [ApiResult.Success] with the created [Document] (in PENDING state) on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if the file fails
     *         pre-condition checks.
     */
    suspend operator fun invoke(
        fileUri: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long
    ): ApiResult<Document> {
        if (sizeBytes > MAX_FILE_SIZE_BYTES) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "File size $sizeBytes bytes exceeds the maximum of $MAX_FILE_SIZE_BYTES bytes (50 MB).",
                    fields = mapOf(FIELD_FILE to "File must be â‰¤ 50 MB.")
                )
            )
        }

        if (mimeType !in ALLOWED_MIME_TYPES) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "File type '$mimeType' is not supported. Accepted types: PDF, DOCX, TXT, Markdown.",
                    fields = mapOf(FIELD_MIME_TYPE to "File must be PDF, DOCX, TXT, or Markdown.")
                )
            )
        }

        return documentRepository.uploadDocument(fileUri, fileName, mimeType)
    }

    companion object {
        /** Maximum allowed file size: 50 MB expressed in bytes. */
        const val MAX_FILE_SIZE_BYTES = 50L * 1024L * 1024L

        /** Form field name for file-level validation errors. */
        const val FIELD_FILE = "file"

        /** Form field name for MIME type validation errors. */
        const val FIELD_MIME_TYPE = "mimeType"

        /** MIME types accepted by the RAG pipeline (Requirement 4.1). */
        val ALLOWED_MIME_TYPES: Set<String> = setOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown",
            "text/x-markdown"
        )
    }
}

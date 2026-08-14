/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DeleteDocumentUseCase.kt
 * Purpose    : Encapsulates the 'DeleteDocument' business operation
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
 * File       : DeleteDocumentUseCase.kt
 * Purpose    : Encapsulates the 'DeleteDocument' business operation
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
 * DeleteDocumentUseCase.kt
 *
 * Purpose: Deletes a document from both the local cache and the backend, including
 *          all associated RAG chunks and embeddings.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), DocumentRepository
 *
 * Requirements: 4.10
 */

package com.aiassistant.domain.usecase.document

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.DocumentRepository
import javax.inject.Inject

/**
 * Use case for deleting a document and all its RAG artefacts.
 *
 * WHEN a User deletes a Document, THE RAG_Pipeline SHALL remove all associated Chunks
 * and Embeddings from the Vector_Store within 60 seconds (Requirement 4.10).
 *
 * @param documentRepository Repository providing the document delete operation.
 */
class DeleteDocumentUseCase @Inject constructor(private val documentRepository: DocumentRepository) {

    /**
     * Executes the document deletion.
     *
     * @param documentId The unique identifier of the document to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend operator fun invoke(documentId: String): ApiResult<Unit> = documentRepository.deleteDocument(documentId)
}

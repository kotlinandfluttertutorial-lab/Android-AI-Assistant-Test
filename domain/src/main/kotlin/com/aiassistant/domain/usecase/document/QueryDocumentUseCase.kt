/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : QueryDocumentUseCase.kt
 * Purpose    : Encapsulates the 'QueryDocument' business operation
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
 * QueryDocumentUseCase.kt
 *
 * Purpose: Submits a natural language query against the RAG index for a specific document
 *          and returns a cited AI-generated response.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), DocumentRepository
 *
 * Requirements: 4.6
 *
 * Design decisions:
 * - Query is validated to be non-blank before reaching the repository.
 * - Citation inclusion is enforced at the backend level; the response string from the
 *   repository already contains inline citations.
 */

package com.aiassistant.domain.usecase.document

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.DocumentRepository
import javax.inject.Inject

/**
 * Use case for querying a document using the RAG pipeline.
 *
 * WHEN a User submits a question about a Document, THE RAG_Pipeline SHALL retrieve the
 * top-K semantically relevant Chunks (default K=5) and assemble them into a context window.
 * THE AI_Orchestrator SHALL include citations referencing the source Document name and page
 * number for each retrieved Chunk (Requirement 4.6).
 *
 * @param documentRepository Repository providing the RAG query operation.
 */
class QueryDocumentUseCase @Inject constructor(private val documentRepository: DocumentRepository) {

    /**
     * Executes a RAG query against the given document.
     *
     * @param documentId The unique identifier of the document to query.
     * @param query      The user's natural language question. Must not be blank.
     * @return [ApiResult.Success] with the cited response text on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] when [query] is blank.
     */
    suspend operator fun invoke(documentId: String, query: String): ApiResult<String> {
        if (query.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Query must not be blank.",
                    fields = mapOf(FIELD_QUERY to "A non-empty query is required.")
                )
            )
        }

        return documentRepository.queryDocument(documentId, query.trim())
    }

    internal companion object {
        const val FIELD_QUERY = "query"
    }
}

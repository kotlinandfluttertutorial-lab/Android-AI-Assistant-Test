/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : QueryDocumentWithSourcesUseCase.kt
 * Purpose    : Submits a RAG question and returns a structured RagAnswer
 *              with per-source excerpts and similarity scores.
 *
 * Architecture Layer : Domain — pure Kotlin, zero Android/framework deps
 *
 * Design Decision:
 *   The existing QueryDocumentUseCase returns a raw String (suitable for the
 *   current text-parsing citation approach).  This use case uses
 *   DocumentRepository.ragQuery() which calls POST /api/v1/rag/query and
 *   returns a structured RagAnswer — no client-side text parsing required.
 *
 *   Both use cases are kept in parallel so existing code is not broken.
 *   Feature modules choose which one to inject.
 *
 * Requirements: 4.6, 4.7
 * ============================================================
 */

package com.aiassistant.domain.usecase.document

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.RagAnswer
import com.aiassistant.domain.repository.DocumentRepository
import javax.inject.Inject

/**
 * Submits a natural-language question to the RAG pipeline and returns a
 * structured [RagAnswer] that includes per-source excerpts and similarity
 * scores (Requirements 4.6, 4.7).
 *
 * Unlike [QueryDocumentUseCase] (which returns a raw String), this use case
 * calls `POST /api/v1/rag/query` via [DocumentRepository.ragQuery] and
 * surfaces citations as typed [com.aiassistant.domain.model.RagCitationSource]
 * objects — no client-side regex parsing is needed.
 *
 * @param documentRepository Repository providing the structured RAG query operation.
 */
class QueryDocumentWithSourcesUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
) {

    /**
     * Executes a structured RAG query.
     *
     * @param question    The user's natural-language question.  Must not be blank.
     * @param documentIds Optional list of document IDs to restrict retrieval.
     *                    Pass `null` or empty to query across all documents.
     * @param topK        Maximum number of chunks to retrieve (default 5).
     *
     * @return [ApiResult.Success] wrapping a [RagAnswer] on success;
     *         [ApiResult.Error] with [DomainError.ValidationError] when [question] is blank.
     */
    suspend operator fun invoke(
        question: String,
        documentIds: List<String>? = null,
        topK: Int = 5,
    ): ApiResult<RagAnswer> {
        if (question.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Question must not be blank.",
                    fields = mapOf(FIELD_QUESTION to "A non-empty question is required."),
                )
            )
        }

        val scopedIds = documentIds?.ifEmpty { null }
        return documentRepository.ragQuery(
            question = question.trim(),
            documentIds = scopedIds,
            topK = topK,
        )
    }

    internal companion object {
        const val FIELD_QUESTION = "question"
    }
}

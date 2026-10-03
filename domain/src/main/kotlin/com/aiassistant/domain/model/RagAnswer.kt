/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : RagAnswer.kt
 * Purpose    : Structured result of a RAG query — answer + rich citations.
 *
 * Architecture Layer : Domain — pure Kotlin, no Android/framework deps
 *
 * Design Decision:
 *   The existing QueryDocumentUseCase returns a raw String (the answer text with
 *   citations embedded as markers).  The new QueryDocumentWithSourcesUseCase returns
 *   a RagAnswer so the UI can render structured citations (excerpt, score) without
 *   needing to parse the response text.
 *
 *   RagCitation is a separate, richer type from the feature-rag Citation value
 *   class — it lives in domain so both feature-rag and future feature-chat can
 *   consume it without a circular dependency.
 *
 * Requirements: 4.6, 4.7
 * ============================================================
 */

package com.aiassistant.domain.model

/**
 * One source citation returned by the `/api/v1/rag/query` endpoint.
 *
 * @param documentName  Display name of the source document (e.g. "annual_report.pdf").
 * @param pageNumber    1-based page number within the document.  Matches the spec field.
 * @param excerpt       Verbatim text excerpt from the retrieved chunk.  Used to display
 *                      a preview under the citation in the UI.
 * @param score         Cosine-similarity score in the range [0, 1].  Higher is more relevant.
 */
data class RagCitationSource(
    val documentName: String,
    val pageNumber: Int,
    val excerpt: String,
    val score: Float,
)

/**
 * The structured result of a RAG query against one or more documents.
 *
 * Returned by [com.aiassistant.domain.usecase.document.QueryDocumentWithSourcesUseCase]
 * and carried through [com.aiassistant.feature.rag.DocumentChatUiState.Success] so the
 * UI can display the answer and expand individual citations.
 *
 * @param answer     LLM-generated answer grounded in the retrieved document chunks.
 * @param sources    Ordered list of source citations, highest-score first.
 * @param requestId  Backend correlation ID for logging and support.
 */
data class RagAnswer(
    val answer: String,
    val sources: List<RagCitationSource>,
    val requestId: String,
) {
    /** True when at least one source citation was returned. */
    val hasSources: Boolean get() = sources.isNotEmpty()
}

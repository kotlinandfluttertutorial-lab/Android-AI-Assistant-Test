/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : DocumentChatUiState.kt
 * Purpose    : UI state types for the DocumentChat screen, extended to carry
 *              structured RagAnswer (excerpt + score per citation).
 *
 * Architecture Layer : Feature (feature-rag) — MVVM presentation layer
 * Requirements       : 4.6, 4.7
 * ============================================================
 */

package com.aiassistant.feature.rag

import com.aiassistant.domain.model.RagAnswer
import com.aiassistant.domain.model.RagCitationSource

/**
 * A single citation entry parsed from a *legacy* raw-string RAG response.
 *
 * Kept for backward compatibility with [DocumentChatViewModel.parseResponse].
 * New code that receives structured results from `POST /api/v1/rag/query` uses
 * [RagCitationSource] directly via [RagAnswer.sources].
 *
 * @param documentName The name of the source document (e.g. "annual_report.pdf").
 * @param pageNumber   1-based page number.  Null for page-less documents.
 */
data class Citation(val documentName: String, val pageNumber: Int?)

/**
 * A complete RAG exchange — user query + AI response + citations.
 *
 * Carries both legacy [Citation] objects (text-parsed) and the richer
 * [RagCitationSource] list (from the structured `/rag/query` endpoint).
 *
 * @param userQuery  Natural-language question submitted by the user.
 * @param aiResponse Full AI-generated response text.
 * @param citations  Legacy citations — populated when text parsing was used.
 *                   Empty when [sources] is populated instead.
 * @param sources    Structured citations from the spec `/api/v1/rag/query` endpoint.
 *                   Each entry carries [RagCitationSource.excerpt] and [RagCitationSource.score].
 *                   Empty when [citations] is populated instead.
 */
data class RAGExchange(
    val userQuery: String,
    val aiResponse: String,
    val citations: List<Citation> = emptyList(),
    val sources: List<RagCitationSource> = emptyList(),
) {
    /** True when at least one source citation is available (either type). */
    val hasAnySources: Boolean get() = citations.isNotEmpty() || sources.isNotEmpty()
}

/**
 * Every possible UI state for the DocumentChat screen.
 *
 * [DocumentChatViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this class.
 */
sealed class DocumentChatUiState {

    /**
     * Initial idle state — no query has been submitted yet.
     *
     * @param documentFileName Display name of the document being queried.
     */
    data class Idle(val documentFileName: String = "") : DocumentChatUiState()

    /**
     * A query has been submitted and the RAG pipeline is processing it.
     *
     * @param query            The query that was submitted.
     * @param documentFileName Display name of the document being queried.
     */
    data class Loading(val query: String, val documentFileName: String = "") : DocumentChatUiState()

    /**
     * The RAG pipeline returned a successful response.
     *
     * @param exchange         [RAGExchange] with query, answer, and citation list.
     * @param documentFileName Display name of the document being queried.
     * @param ragAnswer        Structured [RagAnswer] when the response came from
     *                         `POST /api/v1/rag/query`; null for legacy text-parsed responses.
     */
    data class Success(
        val exchange: RAGExchange,
        val documentFileName: String = "",
        val ragAnswer: RagAnswer? = null,
    ) : DocumentChatUiState()

    /**
     * The RAG query failed.
     *
     * @param message          Human-readable error description.
     * @param lastQuery        Query that was attempted (for retry).
     * @param documentFileName Display name of the document being queried.
     */
    data class Error(
        val message: String,
        val lastQuery: String = "",
        val documentFileName: String = "",
    ) : DocumentChatUiState()
}

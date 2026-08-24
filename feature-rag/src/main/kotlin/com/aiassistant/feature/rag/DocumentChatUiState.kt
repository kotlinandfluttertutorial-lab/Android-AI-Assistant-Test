/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : DocumentChatUiState.kt
 * Purpose    : DocumentChatUiState — feature-rag module component
 *
 * Architecture Layer : Feature (feature-rag)
 * Pattern Used       : UI State Data Class
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
 * Module     : feature-rag
 * File       : DocumentChatUiState.kt
 * Purpose    : DocumentChatUiState — feature-rag module component
 *
 * Architecture Layer : Feature (feature-rag)
 * Pattern Used       : UI State Data Class
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
 * DocumentChatUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the DocumentChat screen,
 *          including idle, loading, success-with-citations, and error states.
 * Architecture: feature-rag â€” MVVM presentation layer.
 * Dependencies: None (pure Kotlin data classes)
 *
 * Requirements: 4.6, 4.7
 */
package com.aiassistant.feature.rag

/**
 * A single citation entry from the RAG response.
 *
 * THE AI_Orchestrator SHALL include citations in every RAG response, referencing the
 * source Document name and page number for each retrieved Chunk (Requirement 4.7).
 *
 * @param documentName The name of the source document (e.g. "annual_report.pdf").
 * @param pageNumber   The 1-based page number within the source document where the
 *                     chunk was retrieved from. Null if the document has no page
 *                     concept (e.g. plain-text files).
 */
data class Citation(val documentName: String, val pageNumber: Int?)

/**
 * A complete RAG exchange: the user's query plus the AI's cited response.
 *
 * @param userQuery  The natural language question submitted by the user.
 * @param aiResponse The full AI-generated response text (may include inline citation
 *                   markers such as "[1]", "[2]" etc.).
 * @param citations  Ordered list of [Citation] objects referenced in [aiResponse].
 *                   Empty only when the backend returns no citations.
 */
data class RAGExchange(val userQuery: String, val aiResponse: String, val citations: List<Citation>)

/**
 * Represents every possible UI state for the DocumentChat screen.
 *
 * [DocumentChatViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Composables observe it and render accordingly.
 */
sealed class DocumentChatUiState {

    /**
     * Initial idle state â€” no query has been submitted yet.
     * The query input field and submit button are shown; the response area is empty.
     *
     * @param documentFileName Display name of the document being queried.
     */
    data class Idle(val documentFileName: String = "") : DocumentChatUiState()

    /**
     * A query has been submitted and the RAG pipeline is processing it.
     * Show a loading/typing indicator in place of the response area.
     *
     * @param query            The query that was submitted.
     * @param documentFileName Display name of the document being queried.
     */
    data class Loading(val query: String, val documentFileName: String = "") : DocumentChatUiState()

    /**
     * The RAG pipeline returned a cited response successfully.
     *
     * @param exchange         The [RAGExchange] containing the query, response, and citations.
     * @param documentFileName Display name of the document being queried.
     */
    data class Success(val exchange: RAGExchange, val documentFileName: String = "") : DocumentChatUiState()

    /**
     * The RAG query failed (network error, backend error, validation error, etc.).
     *
     * @param message          Human-readable description of the error.
     * @param lastQuery        The query that was attempted, so the user can retry it.
     * @param documentFileName Display name of the document being queried.
     */
    data class Error(val message: String, val lastQuery: String = "", val documentFileName: String = "") :
        DocumentChatUiState()
}

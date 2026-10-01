/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : Retriever.kt
 * Purpose    : Domain port for the full RAG (Retrieval-Augmented
 *              Generation) retrieval pipeline.  Combines embedding and
 *              vector search behind a single query interface used by
 *              agents and document chat features.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Port — Hexagonal Architecture)
 *
 * Design Rules:
 *   - Pure Kotlin, zero Android/framework/SDK dependencies.
 *   - Uses only types from domain/agent/ (EmbeddingVector,
 *     VectorSearchResult, VectorChunk from VectorStore.kt).
 *   - [Retriever] does not own embedding or vector storage — those are
 *     injected into the implementation.  This keeps the interface
 *     testable with mock providers.
 *
 * Relationship to existing interfaces:
 *   - [DocumentRepository] (domain/repository/) handles upload/delete
 *     network calls.  [Retriever] handles the semantic query step only.
 *   - [VectorStore] and [EmbeddingProvider] are the sub-ports that
 *     [Retriever] implementations compose.
 *
 * Dependencies: domain/agent/VectorStore.kt, domain/agent/EmbeddingProvider.kt
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

// ── Value objects ─────────────────────────────────────────────────────────────

/**
 * A citation produced by a [Retriever] retrieval call.
 *
 * @param documentId    Source document identifier.
 * @param documentName  Human-readable document name for display.
 * @param pageNumber    Source page (1-based); `null` if not applicable.
 * @param excerpt       Short excerpt from the chunk used to ground the answer.
 * @param similarity    Similarity score of this chunk (0.0–1.0).
 * @param retrievalPath How this chunk was found (`"ann"`, `"bm25"`, `"rrf"`, etc.).
 */
@Serializable
data class RetrievalCitation(
    val documentId: String,
    val documentName: String,
    val pageNumber: Int? = null,
    val excerpt: String = "",
    val similarity: Float = 0f,
    val retrievalPath: String = "ann",
)

/**
 * Complete output of one [Retriever] query.
 *
 * @param query      The original query string.
 * @param chunks     Retrieved chunks ordered by relevance (highest first).
 * @param answer     LLM-generated answer when [Retriever.retrieveAndGenerate]
 *                   was called; empty string otherwise.
 * @param citations  Formatted citations corresponding to [chunks].
 */
@Serializable
data class RetrievalResult(
    val query: String,
    val chunks: List<VectorSearchResult> = emptyList(),
    val answer: String = "",
    val citations: List<RetrievalCitation> = emptyList(),
) {
    /** True when at least one chunk was retrieved. */
    val hasResults: Boolean get() = chunks.isNotEmpty()

    /** True when the retriever also generated an answer. */
    val hasAnswer: Boolean get() = answer.isNotBlank()
}

// ── Error type ────────────────────────────────────────────────────────────────

/**
 * Thrown by [Retriever] when retrieval fails irrecoverably.
 *
 * @param message   Human-readable description.
 * @param retryable True when a retry may succeed.
 * @param cause     Underlying throwable, if any.
 */
class RetrievalException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

// ── Interface ─────────────────────────────────────────────────────────────────

/**
 * Domain port for the RAG retrieval pipeline.
 *
 * Implementations live in `:data` and compose [EmbeddingProvider] +
 * [VectorStore] (and optionally an LLM client for answer generation).
 *
 * Two concrete scenarios:
 * - **On-device**: `OnDeviceRetriever` uses `OnDeviceEmbeddingProviderAdapter`
 *   + `LocalVectorStoreAdapter`.  Zero network calls.
 * - **Cloud-delegated**: `RemoteRetriever` calls `POST /documents/query`
 *   (or `POST /documents/search` for hybrid) and maps the response.
 *
 * ## Usage
 * ```kotlin
 * // In RagAgent.execute():
 * val result = retriever.retrieve(
 *     userId = context.userId,
 *     query = request.input,
 *     topK = 5,
 *     documentIds = context.ragDocumentIds.takeIf { it.isNotEmpty() },
 * )
 * // result.chunks → inject into LLM context
 * // result.citations → attach to AgentResult
 * ```
 */
interface Retriever {

    /**
     * Retrieve chunks relevant to [query] from [userId]'s document corpus.
     *
     * Does NOT call an LLM.  Returns raw retrieved chunks and citations.
     *
     * @param userId      Restrict retrieval to this user's documents.
     * @param query       Natural-language query string.  Must not be blank.
     * @param topK        Maximum number of chunks to return.  Must be ≥ 1.
     * @param documentIds When non-null and non-empty, restricts retrieval to
     *                    these document IDs.  `null` searches the full corpus.
     * @return [RetrievalResult] with populated [RetrievalResult.chunks] and
     *         [RetrievalResult.citations].  [RetrievalResult.answer] is empty.
     * @throws RetrievalException On unrecoverable retrieval failure.
     */
    suspend fun retrieve(
        userId: String,
        query: String,
        topK: Int = 5,
        documentIds: List<String>? = null,
    ): RetrievalResult

    /**
     * Retrieve relevant chunks **and** generate a grounded answer with citations.
     *
     * Extends [retrieve] by passing the assembled chunk context to an LLM and
     * populating [RetrievalResult.answer] and [RetrievalResult.citations].
     *
     * @param userId      Restrict retrieval to this user's documents.
     * @param query       Natural-language query.  Must not be blank.
     * @param topK        Maximum chunks to retrieve before generation.
     * @param documentIds Optional document scope restriction.
     * @return [RetrievalResult] with both [RetrievalResult.chunks] and
     *         [RetrievalResult.answer] populated.
     * @throws RetrievalException On retrieval or generation failure.
     */
    suspend fun retrieveAndGenerate(
        userId: String,
        query: String,
        topK: Int = 5,
        documentIds: List<String>? = null,
    ): RetrievalResult
}

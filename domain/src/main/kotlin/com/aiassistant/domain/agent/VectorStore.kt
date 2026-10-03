/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : VectorStore.kt
 * Purpose    : Domain port for storing and searching dense embedding
 *              vectors.  Abstracts over ChromaDB (backend), pgvector,
 *              FAISS, and the on-device LocalVectorIndex without
 *              exposing any of those libraries at the domain layer.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Port — Hexagonal Architecture)
 *
 * Relationship to existing interfaces:
 *   - [LocalVectorIndex] (core-common/RagContracts.kt) is the on-device
 *     contract using [TextChunk] from RagContracts.  It is intentionally
 *     NOT extended here because its [TextChunk] type would pull in
 *     core-common as a dependency of the domain layer, violating the
 *     module graph.
 *   - [VectorStore] uses [EmbeddingVector] and [VectorSearchResult] defined
 *     in this domain package — zero external dependencies.
 *   - The data layer implements [VectorStore] by delegating to
 *     [LocalVectorIndex] for on-device, or to ChromaDB/pgvector for cloud.
 *
 * Isolation contract:
 *   All operations are scoped to [userId].  Implementations MUST prevent
 *   cross-user data access regardless of similarity scores.
 *
 * Design Rules:
 *   - Pure Kotlin, zero Android/framework/SDK dependencies.
 *   - Uses only types defined in domain/agent/ (EmbeddingVector).
 *
 * Dependencies: domain/agent/EmbeddingProvider.kt
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

// ── Value objects ─────────────────────────────────────────────────────────────

/**
 * One chunk of text stored in a [VectorStore].
 *
 * @param chunkId       Stable identifier (e.g. `"{documentId}_chunk_{index}"`).
 * @param documentId    Parent document identifier.
 * @param documentName  Human-readable document name for citation display.
 * @param userId        Owner of this chunk.
 * @param chunkIndex    Zero-based position within the document.
 * @param pageNumber    Source page (1-based); `null` if not applicable.
 * @param text          Chunk plain-text content.
 * @param charStart     Start character offset in the full document text.
 * @param charEnd       End character offset in the full document text.
 */
@Serializable
data class VectorChunk(
    val chunkId: String,
    val documentId: String,
    val documentName: String,
    val userId: String,
    val chunkIndex: Int,
    val text: String,
    val pageNumber: Int? = null,
    val charStart: Int = 0,
    val charEnd: Int = 0,
) {
    init {
        require(chunkId.isNotBlank()) { "VectorChunk.chunkId must not be blank." }
        require(text.isNotBlank()) { "VectorChunk.text must not be blank." }
        require(userId.isNotBlank()) { "VectorChunk.userId must not be blank." }
    }
}

/**
 * A single result returned by [VectorStore.search].
 *
 * @param chunk          The stored chunk.
 * @param similarity     Cosine similarity to the query vector (0.0–1.0).
 * @param retrievalPath  How this chunk was found:
 *                       `"ann"` (approximate nearest-neighbour),
 *                       `"bm25"` (full-text keyword),
 *                       `"pgvector"` (exact cosine via pgvector),
 *                       `"rrf"` (reciprocal rank fusion of multiple paths).
 */
@Serializable
data class VectorSearchResult(
    val chunk: VectorChunk,
    val similarity: Float,
    val retrievalPath: String = "ann",
) {
    init {
        require(similarity in 0f..1f) {
            "VectorSearchResult.similarity must be in [0.0, 1.0], got $similarity."
        }
    }
}

// ── Error type ────────────────────────────────────────────────────────────────

/**
 * Thrown by [VectorStore] implementations on unrecoverable failure.
 *
 * @param message   Human-readable description.
 * @param retryable True when a retry may succeed (transient I/O error).
 * @param cause     Underlying throwable, if any.
 */
class VectorStoreException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

// ── Interface ─────────────────────────────────────────────────────────────────

/**
 * Domain port for storing and searching dense embedding vectors.
 *
 * Implementations live in `:data`:
 * - `LocalVectorStoreAdapter` — delegates to [com.aiassistant.core.common.LocalVectorIndex].
 * - `RemoteVectorStoreAdapter` — calls the backend's ChromaDB/pgvector via Retrofit.
 *
 * ## Per-user isolation
 * Every operation is scoped to [userId].  The implementation MUST guarantee that
 * a search for user A never returns chunks belonging to user B.
 *
 * ## Usage
 * ```kotlin
 * // Store a chunk after embedding:
 * val embedding = embeddingProvider.embed(chunk.text)
 * vectorStore.upsert(chunk, embedding)
 *
 * // Search:
 * val queryEmbedding = embeddingProvider.embed(userQuery)
 * val results = vectorStore.search(userId, queryEmbedding, topK = 5)
 * ```
 */
interface VectorStore {

    /**
     * Store or update [chunk] together with its [embedding].
     *
     * Upsert semantics: when a chunk with the same [VectorChunk.chunkId]
     * already exists for [chunk.userId], it is replaced atomically.
     *
     * @param chunk     The chunk to persist.
     * @param embedding The dense vector for [chunk.text].
     * @throws VectorStoreException On unrecoverable persistence failure.
     */
    suspend fun upsert(chunk: VectorChunk, embedding: EmbeddingVector)

    /**
     * Return the [topK] chunks most similar to [queryEmbedding] for [userId].
     *
     * Results are ordered by [VectorSearchResult.similarity] descending.
     * Chunks from other users MUST NOT appear regardless of similarity.
     *
     * @param userId          Restrict search to this user's chunks.
     * @param queryEmbedding  Embedding of the query text.
     * @param topK            Maximum number of results.  Must be ≥ 1.
     * @param minSimilarity   Minimum similarity threshold (0.0–1.0).
     *                        Chunks below this score are excluded.
     * @return List of [VectorSearchResult] ordered by similarity descending.
     *         May be empty when no chunks match the threshold.
     */
    suspend fun search(
        userId: String,
        queryEmbedding: EmbeddingVector,
        topK: Int = 5,
        minSimilarity: Float = 0f,
    ): List<VectorSearchResult>

    /**
     * Delete all chunks belonging to [documentId] for [userId].
     *
     * Used during document deletion and re-ingestion.  No-op when no
     * chunks exist for the given document.
     *
     * @param userId     Owner of the document.
     * @param documentId The document whose chunks should be removed.
     */
    suspend fun deleteByDocument(userId: String, documentId: String)

    /**
     * Delete all chunks belonging to [userId].
     *
     * Used for GDPR account deletion.
     *
     * @param userId Owner whose chunks should be purged.
     */
    suspend fun deleteAll(userId: String)

    /**
     * Return the number of stored chunks for [userId].
     *
     * @param userId Owner to count chunks for.
     * @return Non-negative chunk count.
     */
    suspend fun count(userId: String): Int
}

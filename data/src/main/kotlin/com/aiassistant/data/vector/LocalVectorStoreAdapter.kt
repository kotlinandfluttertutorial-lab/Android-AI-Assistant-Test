/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : LocalVectorStoreAdapter.kt
 * Purpose    : Adapts [LocalVectorIndex] (core-common) to the domain
 *              [VectorStore] interface so agents and the on-device RAG
 *              pipeline can store and search vectors without knowing
 *              whether the backend is Room, FAISS, or a remote store.
 *
 * Architecture Layer : Data — vector
 * Pattern Used       : Adapter
 *
 * Design rules:
 *   - Pure Kotlin.  Uses only domain types ([VectorChunk],
 *     [VectorSearchResult], [EmbeddingVector]) and core-common types
 *     ([LocalVectorIndex], [TextChunk], [PageOffset]).
 *   - [LocalVectorIndex] in core-common has NO deleteAll(userId) method.
 *     [VectorStore.deleteAll] is implemented here by fetching all unique
 *     documentIds for the user and deleting each one.
 *   - Converts between domain [VectorChunk] ↔ core-common [TextChunk]
 *     so neither layer leaks into the other.
 *   - [EmbeddingVector.values] (List<Float>) is converted to FloatArray
 *     for the core-common interface.
 * ============================================================
 */

package com.aiassistant.data.vector

import com.aiassistant.core.common.LocalVectorIndex
import com.aiassistant.core.common.PageOffset
import com.aiassistant.core.common.TextChunk
import com.aiassistant.core.database.dao.OnDeviceChunkDao
import com.aiassistant.domain.agent.EmbeddingVector
import com.aiassistant.domain.agent.VectorChunk
import com.aiassistant.domain.agent.VectorSearchResult
import com.aiassistant.domain.agent.VectorStore
import com.aiassistant.domain.agent.VectorStoreException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapts [LocalVectorIndex] to the domain [VectorStore] port.
 *
 * Storage is backed by the on-device Room database via [LocalVectorIndex]
 * (implemented by `LocalVectorIndexImpl` in `core-ai`).  Similarity search
 * is a brute-force linear scan in memory.
 *
 * [deleteAll] is implemented by querying all distinct document IDs for the
 * user from [OnDeviceChunkDao] and deleting each document's chunks in turn,
 * because [LocalVectorIndex] does not expose a bulk-delete operation.
 *
 * @param index   On-device vector index (injected via Hilt).
 * @param dao     Room DAO for querying distinct document IDs for bulk delete.
 */
@Singleton
class LocalVectorStoreAdapter @Inject constructor(
    private val index: LocalVectorIndex,
    private val dao: OnDeviceChunkDao,
) : VectorStore {

    // ── VectorStore interface ─────────────────────────────────────────────────

    /**
     * Store or update [chunk] with its [embedding].
     *
     * Upsert semantics come from Room's `OnConflictStrategy.REPLACE` in
     * [OnDeviceChunkDao.insert].  If a chunk with the same primary key
     * already exists it is replaced atomically.
     *
     * @throws VectorStoreException On Room I/O failure.
     */
    override suspend fun upsert(chunk: VectorChunk, embedding: EmbeddingVector) {
        try {
            val textChunk = chunk.toTextChunk()
            val floatArray = embedding.values.toFloatArray()
            index.addChunk(
                userId = chunk.userId,
                chunk = textChunk,
                embedding = floatArray,
            )
        } catch (e: VectorStoreException) {
            throw e
        } catch (e: Exception) {
            throw VectorStoreException(
                message = "upsert failed for chunk ${chunk.chunkId}: ${e.message}",
                retryable = false,
                cause = e,
            )
        }
    }

    /**
     * Return the [topK] chunks most similar to [queryEmbedding] for [userId].
     *
     * Similarity is cosine similarity computed by [LocalVectorIndexImpl].
     * Results are ordered by similarity descending and filtered by
     * [minSimilarity].
     *
     * @throws VectorStoreException On Room I/O failure.
     */
    override suspend fun search(
        userId: String,
        queryEmbedding: EmbeddingVector,
        topK: Int,
        minSimilarity: Float,
    ): List<VectorSearchResult> {
        return try {
            val queryFloats = queryEmbedding.values.toFloatArray()
            index.search(
                userId = userId,
                queryEmbedding = queryFloats,
                k = topK,
                minSimilarity = minSimilarity,
            ).map { result ->
                VectorSearchResult(
                    chunk = VectorChunk(
                        chunkId = result.id,
                        documentId = result.documentId,
                        documentName = "",    // not stored in ChunkSearchResult
                        userId = userId,
                        chunkIndex = 0,       // not stored in ChunkSearchResult
                        text = result.content,
                    ),
                    similarity = result.cosineSimilarity.coerceIn(0f, 1f),
                    retrievalPath = "local_ann",
                )
            }
        } catch (e: VectorStoreException) {
            throw e
        } catch (e: Exception) {
            throw VectorStoreException(
                message = "search failed for user $userId: ${e.message}",
                retryable = false,
                cause = e,
            )
        }
    }

    /**
     * Delete all chunks belonging to [documentId] for [userId].
     *
     * @throws VectorStoreException On Room I/O failure.
     */
    override suspend fun deleteByDocument(userId: String, documentId: String) {
        try {
            index.deleteByDocument(userId = userId, documentId = documentId)
        } catch (e: Exception) {
            throw VectorStoreException(
                message = "deleteByDocument failed (user=$userId doc=$documentId): ${e.message}",
                retryable = false,
                cause = e,
            )
        }
    }

    /**
     * Delete ALL chunks for [userId].
     *
     * [LocalVectorIndex] has no bulk-delete; we fetch all chunks from
     * [OnDeviceChunkDao], collect the distinct document IDs, then call
     * [deleteByDocument] for each one.
     *
     * @throws VectorStoreException On Room I/O failure.
     */
    override suspend fun deleteAll(userId: String) {
        try {
            val allChunks = dao.getAllChunks(userId)
            val distinctDocIds = allChunks.map { it.documentId }.toSet()
            distinctDocIds.forEach { docId ->
                index.deleteByDocument(userId = userId, documentId = docId)
            }
        } catch (e: Exception) {
            throw VectorStoreException(
                message = "deleteAll failed for user $userId: ${e.message}",
                retryable = false,
                cause = e,
            )
        }
    }

    /**
     * Return the number of stored chunks for [userId].
     */
    override suspend fun count(userId: String): Int {
        return try {
            dao.countChunks(userId)
        } catch (e: Exception) {
            throw VectorStoreException(
                message = "count failed for user $userId: ${e.message}",
                retryable = false,
                cause = e,
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun VectorChunk.toTextChunk(): TextChunk = TextChunk(
        id = chunkId,
        documentId = documentId,
        documentName = documentName,
        chunkIndex = chunkIndex,
        pageNumber = pageNumber,
        startCharOffset = charStart,
        endCharOffset = charEnd,
        content = text,
    )
}

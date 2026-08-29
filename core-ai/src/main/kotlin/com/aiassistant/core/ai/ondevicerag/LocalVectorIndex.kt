/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : LocalVectorIndex.kt
 * Purpose    : In-process cosine similarity vector index backed by Room's
 *              OnDeviceChunkDao.  Provides add / search / delete / stats
 *              operations used by the on-device RAG query and ingestion
 *              pipelines.
 *
 * Architecture Layer : Core-AI — on-device RAG pipeline (indexing stage 4 of 4
 *                      and query stage 2 of 3).
 *                      Only LocalVectorIndex reads/writes OnDeviceChunkDao.
 *                      Feature modules access retrieval through the domain's
 *                      OnDeviceQueryUseCase interface.
 *
 * Dependencies       : core-database (OnDeviceChunkDao, OnDeviceChunkEntity),
 *                      Hilt (injection), kotlinx.coroutines.
 *
 * Design Decision    : Cosine similarity is computed entirely in-process on the
 *                      Kotlin/JVM side.  SQLite cannot perform vector math, and
 *                      external vector-DB libraries (Faiss, Annoy) would add
 *                      several MB of native code.  For the expected corpus size
 *                      (< 10 000 chunks × 384 floats = ~15 MB), loading all
 *                      vectors into memory and running a linear scan is fast
 *                      enough (< 200 ms on a mid-range device).
 *
 *                      User isolation is enforced at the SQL WHERE clause level
 *                      in OnDeviceChunkDao, not only in-memory.  LocalVectorIndex
 *                      must not rely on post-load filtering as the sole guard.
 *
 *                      Stored and query vectors are L2-normalised before the dot
 *                      product so the result equals cosine similarity directly.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import com.aiassistant.core.database.dao.OnDeviceChunkDao
import com.aiassistant.core.database.entity.OnDeviceChunkEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Summary statistics for the local vector index owned by a single user.
 *
 * @param totalChunks     Number of indexed chunks.
 * @param totalDocuments  Number of distinct documents with at least one chunk.
 * @param indexSizeBytes  Estimated storage used by embedding blobs in bytes.
 */
data class VectorIndexStats(
    val totalChunks: Int,
    val totalDocuments: Int,
    val indexSizeBytes: Long,
)

/**
 * One search result returned by [LocalVectorIndex.search].
 *
 * @param chunk           The matching [OnDeviceChunkEntity].
 * @param cosineSimilarity Cosine similarity score in [minSimilarity, 1.0].
 */
data class ChunkSearchResult(
    val chunk: OnDeviceChunkEntity,
    val cosineSimilarity: Float,
)

/** Default minimum similarity threshold — chunks below this are not returned. */
const val DEFAULT_MIN_SIMILARITY = 0.40f

/**
 * In-process cosine similarity search over embeddings stored in Room.
 *
 * All public methods are `suspend` and must be called from a coroutine.  They
 * delegate I/O to [dao] which runs on Room's executor.
 */
@Singleton
class LocalVectorIndex @Inject constructor(
    private val dao: OnDeviceChunkDao,
) {

    /**
     * Stores [chunk] and its [embedding] in the index.
     *
     * If a chunk with the same [OnDeviceChunkEntity.id] already exists it is
     * overwritten (Room `REPLACE` strategy) — re-ingesting a document produces
     * fresh embeddings without orphaned rows.
     *
     * @param userId    Owner of the document.  Stored in the row for SQL-level isolation.
     * @param chunk     The [TextChunk] produced by [Chunker].
     * @param embedding L2-normalised float32 embedding from [OnDeviceEmbeddingModel].
     */
    suspend fun addChunk(userId: String, chunk: TextChunk, embedding: FloatArray) {
        val entity = OnDeviceChunkEntity(
            id = chunk.id,
            userId = userId,
            documentId = chunk.documentId,
            documentName = chunk.documentName,
            chunkIndex = chunk.chunkIndex,
            pageNumber = chunk.pageNumber,
            startCharOffset = chunk.startCharOffset,
            endCharOffset = chunk.endCharOffset,
            content = chunk.content,
            embeddingBlob = embedding,
            createdAt = System.currentTimeMillis(),
        )
        dao.insert(entity)
    }

    /**
     * Performs a linear cosine similarity scan over all chunks owned by [userId].
     *
     * Steps:
     * 1. Load all chunks from Room for [userId] (SQL WHERE enforces user isolation).
     * 2. L2-normalise the [queryEmbedding] (stored embeddings are already normalised).
     * 3. Compute dot product between query and each stored embedding → cosine similarity.
     * 4. Filter by [minSimilarity] and return top-[k] results sorted descending.
     *
     * Returns an empty list when no chunks meet the threshold.
     *
     * @param userId          Owner whose chunks to search.
     * @param queryEmbedding  Raw float32 embedding of the user's query text.
     * @param k               Maximum number of results to return.
     * @param minSimilarity   Minimum cosine similarity to include (default 0.40).
     */
    suspend fun search(
        userId: String,
        queryEmbedding: FloatArray,
        k: Int,
        minSimilarity: Float = DEFAULT_MIN_SIMILARITY,
    ): List<ChunkSearchResult> {
        val allChunks = dao.getAllChunks(userId)
        if (allChunks.isEmpty()) return emptyList()

        val normalisedQuery = l2Normalize(queryEmbedding.copyOf())

        return allChunks
            .map { entity ->
                val similarity = dotProduct(normalisedQuery, entity.embeddingBlob)
                ChunkSearchResult(entity, similarity)
            }
            .filter { it.cosineSimilarity >= minSimilarity }
            .sortedByDescending { it.cosineSimilarity }
            .take(k)
    }

    /**
     * Removes all chunks belonging to [documentId] for [userId].
     *
     * The parent [OnDeviceChunkEntity] foreign key has CASCADE DELETE, so removing
     * the document row also removes chunks.  This explicit call is kept so the
     * deletion path is measurable in unit tests (10-second SLA).
     */
    suspend fun deleteByDocument(userId: String, documentId: String) {
        dao.deleteByDocument(userId, documentId)
    }

    /**
     * Returns storage and count statistics for [userId]'s index.
     *
     * @param embeddingDimension Dimension of the embedding model in use (e.g. 384).
     */
    suspend fun getStats(userId: String, embeddingDimension: Int = MINI_LM_EMBEDDING_DIM): VectorIndexStats {
        val totalChunks = dao.countChunks(userId)
        val totalDocuments = dao.getAllChunks(userId)
            .map { it.documentId }
            .distinct()
            .size
        val indexSizeBytes = dao.totalEmbeddingBytes(userId, embeddingDimension)

        return VectorIndexStats(
            totalChunks = totalChunks,
            totalDocuments = totalDocuments,
            indexSizeBytes = indexSizeBytes,
        )
    }

    // ── Vector math helpers ───────────────────────────────────────────────────

    /**
     * L2-normalises [vector] in-place and returns it.
     * A zero vector is returned unchanged to avoid NaN propagation.
     */
    internal fun l2Normalize(vector: FloatArray): FloatArray {
        val norm = Math.sqrt(vector.fold(0.0) { acc, v -> acc + v * v }).toFloat()
        if (norm < 1e-9f) return vector
        for (i in vector.indices) vector[i] /= norm
        return vector
    }

    /**
     * Dot product of two equal-length float vectors.
     * When both are L2-normalised this equals cosine similarity.
     */
    internal fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) sum += a[i] * b[i]
        return sum
    }
}

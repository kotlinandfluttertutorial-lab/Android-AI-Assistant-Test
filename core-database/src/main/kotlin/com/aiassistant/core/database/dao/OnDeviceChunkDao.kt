/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : OnDeviceChunkDao.kt
 * Purpose    : Room DAO for the on_device_chunks table.  Used exclusively
 *              by LocalVectorIndex (core-ai) to store, retrieve, and remove
 *              text chunks and their embedding vectors.
 *
 * Architecture Layer : Core-Database — data access layer.
 *                      LocalVectorIndex is the only consumer; it receives
 *                      this DAO via Hilt injection through AppDatabase.
 *
 * Dependencies       : Room, OnDeviceChunkEntity, kotlinx.coroutines.flow
 *
 * Design Decision    : getAllChunks() returns a plain List (not Flow) because
 *                      the cosine similarity search in LocalVectorIndex loads
 *                      all embeddings into memory in one shot for in-process
 *                      dot-product computation.  A Flow would add reactive
 *                      overhead with no benefit for a one-shot search call.
 *                      User isolation is enforced at the SQL WHERE clause
 *                      level — LocalVectorIndex must not rely on in-memory
 *                      filtering as an additional safety net.
 *                      totalEmbeddingBytes() uses COUNT * 4 * embeddingDim
 *                      approximation; the exact byte size is not stored per
 *                      row to avoid redundancy.
 * ============================================================
 */
package com.aiassistant.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiassistant.core.database.entity.OnDeviceChunkEntity

@Dao
interface OnDeviceChunkDao {

    companion object {
        private const val FLOAT_SIZE_BYTES = 4L
    }

    /**
     * Inserts or overwrites a chunk.  REPLACE strategy implements the
     * "overwrite existing entry with same chunk.id" requirement from the spec
     * — re-ingesting a document produces fresh embeddings that replace the old
     * ones without leaving orphaned rows.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: OnDeviceChunkEntity)

    /**
     * Batch insert for efficiency during the embedding phase of ingestion
     * (typically 10–200 chunks per document).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<OnDeviceChunkEntity>)

    /**
     * Returns all chunks for a specific document owned by [userId].
     * Used to verify ingestion completeness and to enumerate citations.
     */
    @Query(
        "SELECT * FROM on_device_chunks WHERE userId = :userId AND documentId = :documentId ORDER BY chunkIndex ASC"
    )
    suspend fun getChunksForDocument(userId: String, documentId: String): List<OnDeviceChunkEntity>

    /**
     * Returns every chunk owned by [userId].  LocalVectorIndex loads this
     * into memory to perform the in-process cosine similarity search.
     *
     * Performance note: on a device with 384-dim embeddings this is
     * 1,536 bytes × N rows.  At 10,000 chunks that is ~15 MB — acceptable
     * for on-device RAG.  LocalVectorIndex should warn (but not crash) when
     * chunk count exceeds a configurable high-water mark.
     */
    @Query("SELECT * FROM on_device_chunks WHERE userId = :userId")
    suspend fun getAllChunks(userId: String): List<OnDeviceChunkEntity>

    /**
     * Removes all chunks belonging to a document owned by [userId].
     * Called by DeleteOnDeviceDocumentUseCase before removing the parent
     * document row (which would also CASCADE-delete these rows, but this
     * explicit call makes the 10-second deletion SLA measurable in tests).
     */
    @Query("DELETE FROM on_device_chunks WHERE userId = :userId AND documentId = :documentId")
    suspend fun deleteByDocument(userId: String, documentId: String)

    /**
     * Returns the total number of chunks stored for [userId].
     * Exposed via VectorIndexStats to drive the ManageModelsScreen display.
     */
    @Query("SELECT COUNT(*) FROM on_device_chunks WHERE userId = :userId")
    suspend fun countChunks(userId: String): Int

    /**
     * Estimates total embedding storage in bytes for [userId].
     *
     * Formula: chunk_count × embedding_dimension × 4 bytes per float32.
     * [embeddingDimension] is injected by the caller (LocalVectorIndex knows
     * the model's output dimension) rather than stored per row.
     *
     * @param embeddingDimension Number of floats per embedding (e.g. 384 for MiniLM).
     */
    suspend fun totalEmbeddingBytes(userId: String, embeddingDimension: Int): Long =
        countChunks(userId).toLong() * embeddingDimension * FLOAT_SIZE_BYTES
}

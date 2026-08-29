/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : LocalVectorIndexImpl.kt
 * Purpose    : Implementation of LocalVectorIndex backed by Room.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import com.aiassistant.core.common.ChunkSearchResult
import com.aiassistant.core.common.LocalVectorIndex
import com.aiassistant.core.common.TextChunk
import com.aiassistant.core.database.dao.OnDeviceChunkDao
import com.aiassistant.core.database.entity.OnDeviceChunkEntity
import javax.inject.Inject

private const val MIN_NORM_THRESHOLD = 1e-9f

class LocalVectorIndexImpl @Inject constructor(
    private val dao: OnDeviceChunkDao
) : LocalVectorIndex {

    override suspend fun addChunk(userId: String, chunk: TextChunk, embedding: FloatArray) {
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

    override suspend fun search(
        userId: String,
        queryEmbedding: FloatArray,
        k: Int,
        minSimilarity: Float,
    ): List<ChunkSearchResult> {
        val allChunks = dao.getAllChunks(userId)
        if (allChunks.isEmpty()) return emptyList()

        val normalisedQuery = l2Normalize(queryEmbedding.copyOf())

        return allChunks
            .map { entity ->
                val similarity = dotProduct(normalisedQuery, entity.embeddingBlob)
                ChunkSearchResult(
                    id = entity.id,
                    documentId = entity.documentId,
                    content = entity.content,
                    cosineSimilarity = similarity
                )
            }
            .filter { it.cosineSimilarity >= minSimilarity }
            .sortedByDescending { it.cosineSimilarity }
            .take(k)
    }

    override suspend fun deleteByDocument(userId: String, documentId: String) {
        dao.deleteByDocument(userId, documentId)
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        val norm = Math.sqrt(vector.fold(0.0) { acc, v -> acc + v * v }).toFloat()
        if (norm < MIN_NORM_THRESHOLD) return vector
        for (i in vector.indices) vector[i] /= norm
        return vector
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) sum += a[i] * b[i]
        return sum
    }
}

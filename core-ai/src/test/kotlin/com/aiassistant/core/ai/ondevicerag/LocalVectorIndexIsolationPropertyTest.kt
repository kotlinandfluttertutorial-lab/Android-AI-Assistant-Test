/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : LocalVectorIndexIsolationPropertyTest.kt
 * Purpose    : Property tests for local vector index user isolation.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import com.aiassistant.core.common.TextChunk
import com.aiassistant.core.database.entity.OnDeviceChunkEntity
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.util.UUID

class LocalVectorIndexIsolationPropertyTest : DescribeSpec({

    describe("Property 39 — Local Vector Index User Isolation") {

        it("user A search never returns chunks belonging to user B") {
            checkAll(
                iterations = 50,
                Arb.list(Arb.string(minSize = 1, maxSize = 80), range = 1..10)
            ) { chunkTexts ->
                val userA = "user_A_${UUID.randomUUID()}"
                val userB = "user_B_${UUID.randomUUID()}"

                val dao = InMemoryOnDeviceChunkDaoV2()
                val index = LocalVectorIndexImpl(dao)

                chunkTexts.forEachIndexed { idx, text ->
                    val chunk = TextChunk(
                        id = "b_chunk_$idx",
                        documentId = "doc_b",
                        documentName = "b.txt",
                        chunkIndex = idx,
                        pageNumber = null,
                        startCharOffset = 0,
                        endCharOffset = text.length,
                        content = text,
                    )
                    index.addChunk(userB, chunk, FloatArray(384) { 0.1f })
                }

                val results = index.search(userA, FloatArray(384) { 0.1f }, k = 100, minSimilarity = 0f)
                results.size shouldBe 0

                val userBChunkIds = chunkTexts.indices.map { "b_chunk_$it" }.toSet()
                results.none { it.id in userBChunkIds } shouldBe true
            }
        }
    }
})

private class InMemoryOnDeviceChunkDaoV2 : com.aiassistant.core.database.dao.OnDeviceChunkDao {
    private val store = mutableListOf<OnDeviceChunkEntity>()

    override suspend fun insert(chunk: OnDeviceChunkEntity) {
        store.removeAll { it.id == chunk.id }
        store.add(chunk)
    }

    override suspend fun insertAll(chunks: List<OnDeviceChunkEntity>) {
        chunks.forEach { insert(it) }
    }

    override suspend fun getChunksForDocument(userId: String, documentId: String) =
        store.filter { it.userId == userId && it.documentId == documentId }

    override suspend fun getAllChunks(userId: String): List<OnDeviceChunkEntity> =
        store.filter { it.userId == userId }

    override suspend fun deleteByDocument(userId: String, documentId: String) {
        store.removeAll { it.userId == userId && it.documentId == documentId }
    }

    override suspend fun countChunks(userId: String): Int =
        store.count { it.userId == userId }

    override suspend fun totalEmbeddingBytes(userId: String, embeddingDimension: Int): Long = 0L
}

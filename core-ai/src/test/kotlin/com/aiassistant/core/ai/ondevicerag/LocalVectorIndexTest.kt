/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : LocalVectorIndexTest.kt
 * Purpose    : Unit tests for LocalVectorIndex.
 *              Validates:
 *                1. Insert then search returns correct results.
 *                2. User A query never returns User B chunks (isolation).
 *                3. Empty result when no chunks above minSimilarity threshold.
 *
 * Architecture Layer : Core-AI test — verifies the vector index component.
 *
 * Requirements: 34.2, 34.3, 34.4, 34.8, 34.9
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import com.aiassistant.core.database.dao.OnDeviceChunkDao
import com.aiassistant.core.database.entity.OnDeviceChunkEntity
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk

class LocalVectorIndexTest : DescribeSpec({

    // ── Helper to create a fake OnDeviceChunkEntity ────────────────────────

    fun fakeEntity(
        id: String,
        userId: String,
        documentId: String = "doc1",
        embedding: FloatArray,
    ) = OnDeviceChunkEntity(
        id = id,
        userId = userId,
        documentId = documentId,
        documentName = "test.txt",
        chunkIndex = 0,
        pageNumber = null,
        startCharOffset = 0,
        endCharOffset = 10,
        content = "Sample content",
        embeddingBlob = embedding,
        createdAt = System.currentTimeMillis(),
    )

    // ── Helper: unit vector in direction of single component ──────────────

    fun unitVec(dim: Int, hotIndex: Int): FloatArray =
        FloatArray(dim).also { it[hotIndex] = 1f }

    describe("LocalVectorIndex — search()") {

        it("returns matching chunk when similarity meets threshold") {
            val dao = mockk<OnDeviceChunkDao>()
            val index = LocalVectorIndex(dao)

            val queryVec = unitVec(4, 0)   // [1, 0, 0, 0]
            val matchVec = unitVec(4, 0)   // cosine similarity = 1.0
            val noMatchVec = unitVec(4, 3) // cosine similarity = 0.0

            coEvery { dao.getAllChunks("userA") } returns listOf(
                fakeEntity("c1", "userA", embedding = matchVec),
                fakeEntity("c2", "userA", embedding = noMatchVec),
            )

            val results = index.search("userA", queryVec, k = 5, minSimilarity = 0.5f)

            results shouldHaveSize 1
            results[0].chunk.id shouldBe "c1"
            results[0].cosineSimilarity shouldBe 1.0f
        }

        it("returns empty list when no chunks exist") {
            val dao = mockk<OnDeviceChunkDao>()
            coEvery { dao.getAllChunks("userA") } returns emptyList()
            val index = LocalVectorIndex(dao)

            index.search("userA", FloatArray(4) { 0.25f }, k = 5).shouldBeEmpty()
        }

        it("returns empty list when all similarity scores are below minSimilarity") {
            val dao = mockk<OnDeviceChunkDao>()
            val index = LocalVectorIndex(dao)

            val queryVec = unitVec(4, 0)      // [1, 0, 0, 0]
            val oppositeVec = unitVec(4, 3)   // orthogonal → similarity = 0.0

            coEvery { dao.getAllChunks("userA") } returns listOf(
                fakeEntity("c1", "userA", embedding = oppositeVec),
            )

            index.search("userA", queryVec, k = 5, minSimilarity = 0.5f).shouldBeEmpty()
        }

        it("enforces user isolation — User A query never returns User B chunks") {
            val dao = mockk<OnDeviceChunkDao>()
            val index = LocalVectorIndex(dao)

            // DAO is mocked to only return chunks for the queried userId (SQL WHERE clause)
            val userBVec = unitVec(4, 0) // highly similar to any [1,0,0,0] query
            coEvery { dao.getAllChunks("userA") } returns emptyList()
            coEvery { dao.getAllChunks("userB") } returns listOf(
                fakeEntity("c_b1", "userB", embedding = userBVec),
            )

            val results = index.search("userA", unitVec(4, 0), k = 5, minSimilarity = 0f)
            results.shouldBeEmpty()
        }

        it("returns top-k results sorted by similarity descending") {
            val dao = mockk<OnDeviceChunkDao>()
            val index = LocalVectorIndex(dao)

            // Three chunks with known similarities to [1,0,0,0]:
            //   c1: [1, 0, 0, 0] → sim = 1.0
            //   c2: [0.6, 0.8, 0, 0] → sim ≈ 0.6 (after normalisation it's L2-normalised)
            //   c3: [0, 1, 0, 0] → sim = 0.0 (orthogonal)
            val vec1 = floatArrayOf(1f, 0f, 0f, 0f)
            val vec2 = floatArrayOf(0.6f, 0.8f, 0f, 0f) // already unit-length (0.36+0.64=1)
            val vec3 = floatArrayOf(0f, 1f, 0f, 0f)

            coEvery { dao.getAllChunks("u") } returns listOf(
                fakeEntity("c1", "u", embedding = vec1),
                fakeEntity("c2", "u", embedding = vec2),
                fakeEntity("c3", "u", embedding = vec3),
            )

            val results = index.search("u", vec1, k = 2, minSimilarity = 0.5f)

            results shouldHaveSize 2
            results[0].chunk.id shouldBe "c1"
            results[1].chunk.id shouldBe "c2"
        }
    }

    describe("LocalVectorIndex — vector math helpers") {

        val dao = mockk<OnDeviceChunkDao>()
        val index = LocalVectorIndex(dao)

        it("l2Normalize returns unit vector") {
            val v = floatArrayOf(3f, 4f)  // magnitude = 5
            val normalised = index.l2Normalize(v)
            val magnitude = Math.sqrt((normalised[0] * normalised[0] + normalised[1] * normalised[1]).toDouble())
            (magnitude > 0.999 && magnitude < 1.001) shouldBe true
        }

        it("l2Normalize returns zero vector unchanged") {
            val v = floatArrayOf(0f, 0f, 0f)
            val result = index.l2Normalize(v)
            result.contentEquals(floatArrayOf(0f, 0f, 0f)) shouldBe true
        }

        it("dotProduct of two unit vectors equals cosine similarity") {
            val a = floatArrayOf(1f, 0f)
            val b = floatArrayOf(0f, 1f)
            index.dotProduct(a, b) shouldBe 0f

            val c = floatArrayOf(1f, 0f)
            index.dotProduct(a, c) shouldBe 1f
        }
    }
})

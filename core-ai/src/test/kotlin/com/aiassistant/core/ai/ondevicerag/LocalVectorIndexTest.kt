/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : LocalVectorIndexTest.kt
 * Purpose    : Unit tests for LocalVectorIndexImpl.
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
        createdAt = 0,
    )

    fun unitVec(dim: Int, hotIndex: Int): FloatArray =
        FloatArray(dim).also { it[hotIndex] = 1f }

    describe("LocalVectorIndex — search()") {

        it("returns matching chunk when similarity meets threshold") {
            val dao = mockk<OnDeviceChunkDao>()
            val index = LocalVectorIndexImpl(dao)

            val matchVec = unitVec(4, 0)
            coEvery { dao.getAllChunks("userA") } returns listOf(
                fakeEntity("c1", "userA", embedding = matchVec),
            )

            val results = index.search("userA", unitVec(4, 0), k = 5, minSimilarity = 0.5f)

            results shouldHaveSize 1
            results[0].id shouldBe "c1"
            results[0].cosineSimilarity shouldBe 1.0f
        }

        it("enforces user isolation") {
            val dao = mockk<OnDeviceChunkDao>()
            val index = LocalVectorIndexImpl(dao)

            coEvery { dao.getAllChunks("userA") } returns emptyList()
            coEvery { dao.getAllChunks("userB") } returns listOf(
                fakeEntity("c_b1", "userB", embedding = unitVec(4, 0)),
            )

            val results = index.search("userA", unitVec(4, 0), k = 5, minSimilarity = 0f)
            results.shouldBeEmpty()
        }
    }
})

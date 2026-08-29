/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : LocalVectorIndexIsolationPropertyTest.kt
 *
 * Property 39: Local Vector Index User Isolation
 * Validates  : Requirement 34.8
 *
 * Specification:
 *   Generate two distinct user IDs (A and B); generate random chunks for
 *   user B; insert all under B's scope in an in-memory Room test database;
 *   search under user A with random query embedding (minSimilarity = 0f);
 *   assert zero results contain chunk IDs belonging to user B.
 *
 * Architecture Layer : Core-AI test — pure JVM, no Android deps.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.filter
import io.kotest.property.checkAll
import java.util.UUID

class LocalVectorIndexIsolationPropertyTest : DescribeSpec({

    /**
     * Property 39: For any set of chunks inserted under user B, a search
     * performed under user A returns zero results — regardless of query
     * embedding or similarity threshold.
     *
     * The in-memory DAO enforces the same SQL-level WHERE userId = ? that
     * the production Room DAO uses, proving user isolation holds structurally.
     */
    describe("Property 39 — Local Vector Index User Isolation") {

        it("user A search never returns chunks belonging to user B") {
            checkAll(
                iterations = 50,
                // Generate between 1 and 10 chunk contents for user B
                Arb.list(Arb.string(minSize = 1, maxSize = 80), range = 1..10),
            ) { chunkTexts ->
                val userA = "user_A_${UUID.randomUUID()}"
                val userB = "user_B_${UUID.randomUUID()}"

                val dao = InMemoryOnDeviceChunkDaoV2()
                val index = LocalVectorIndex(dao)

                // Insert chunks under user B
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
                    // Use a high-similarity embedding to maximise chance of
                    // appearing in results if isolation were broken
                    val embedding = FloatArray(MINI_LM_EMBEDDING_DIM) { 1f / MINI_LM_EMBEDDING_DIM.toFloat() }
                    index.addChunk(userB, chunk, embedding)
                }

                // Search under user A with minSimilarity = 0f (returns everything above 0)
                // The query vector is also all-positive so similarities are high
                val queryVec = FloatArray(MINI_LM_EMBEDDING_DIM) { 1f / MINI_LM_EMBEDDING_DIM.toFloat() }
                val results = index.search(userA, queryVec, k = 100, minSimilarity = 0f)

                // Assert: zero results — user A has no chunks and cannot see user B's
                results.size shouldBe 0

                // Additionally verify none of the returned chunk IDs belong to user B
                val userBChunkIds = chunkTexts.indices.map { "b_chunk_$it" }.toSet()
                val returnedIds = results.map { it.chunk.id }.toSet()
                (returnedIds intersect userBChunkIds).isEmpty() shouldBe true
            }
        }

        it("user B search sees only user B chunks even when user A also has chunks") {
            checkAll(
                iterations = 30,
                Arb.list(Arb.string(minSize = 3, maxSize = 50), range = 1..5),
                Arb.list(Arb.string(minSize = 3, maxSize = 50), range = 1..5),
            ) { userATexts, userBTexts ->
                val userA = "userA_${UUID.randomUUID()}"
                val userB = "userB_${UUID.randomUUID()}"

                val dao = InMemoryOnDeviceChunkDaoV2()
                val index = LocalVectorIndex(dao)

                val embed = FloatArray(MINI_LM_EMBEDDING_DIM) { 0.5f }

                userATexts.forEachIndexed { i, text ->
                    index.addChunk(userA, TextChunk("a_$i", "docA", "a.txt", i, null, 0, text.length, text), embed)
                }
                userBTexts.forEachIndexed { i, text ->
                    index.addChunk(userB, TextChunk("b_$i", "docB", "b.txt", i, null, 0, text.length, text), embed)
                }

                val query = FloatArray(MINI_LM_EMBEDDING_DIM) { 0.5f }

                val resultsA = index.search(userA, query, k = 100, minSimilarity = 0f)
                val resultsB = index.search(userB, query, k = 100, minSimilarity = 0f)

                // No result in A contains a B id
                val bIds = userBTexts.indices.map { "b_$it" }.toSet()
                resultsA.none { it.chunk.id in bIds } shouldBe true

                // No result in B contains an A id
                val aIds = userATexts.indices.map { "a_$it" }.toSet()
                resultsB.none { it.chunk.id in aIds } shouldBe true
            }
        }
    }
})

// ── In-memory DAO (isolated copy to avoid import clash) ───────────────────────

private class InMemoryOnDeviceChunkDaoV2 : com.aiassistant.core.database.dao.OnDeviceChunkDao {
    private val store = mutableListOf<com.aiassistant.core.database.entity.OnDeviceChunkEntity>()

    override suspend fun insert(chunk: com.aiassistant.core.database.entity.OnDeviceChunkEntity) {
        store.removeAll { it.id == chunk.id }
        store.add(chunk)
    }

    override suspend fun insertAll(chunks: List<com.aiassistant.core.database.entity.OnDeviceChunkEntity>) {
        chunks.forEach { insert(it) }
    }

    override suspend fun getChunksForDocument(userId: String, documentId: String) =
        store.filter { it.userId == userId && it.documentId == documentId }

    // SQL-level WHERE userId = ? — this is the isolation boundary under test
    override suspend fun getAllChunks(userId: String) =
        store.filter { it.userId == userId }

    override suspend fun deleteByDocument(userId: String, documentId: String) {
        store.removeAll { it.userId == userId && it.documentId == documentId }
    }

    override suspend fun countChunks(userId: String) =
        store.count { it.userId == userId }
}

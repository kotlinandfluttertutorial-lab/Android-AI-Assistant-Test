/*
 * Domain unit tests for VectorStore interface and its value objects:
 * VectorChunk, VectorSearchResult, VectorStoreException.
 */
package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

// ── Minimal in-memory stub ────────────────────────────────────────────────────

private class InMemoryVectorStore : VectorStore {
    data class Entry(val chunk: VectorChunk, val embedding: EmbeddingVector)

    private val store = mutableMapOf<String, Entry>()  // chunkId → Entry

    override suspend fun upsert(chunk: VectorChunk, embedding: EmbeddingVector) {
        store[chunk.chunkId] = Entry(chunk, embedding)
    }

    override suspend fun search(
        userId: String,
        queryEmbedding: EmbeddingVector,
        topK: Int,
        minSimilarity: Float,
    ): List<VectorSearchResult> {
        return store.values
            .filter { it.chunk.userId == userId }
            .mapNotNull { entry ->
                val sim = queryEmbedding.cosineSimilarity(entry.embedding)
                    .coerceIn(0f, 1f)
                if (sim >= minSimilarity)
                    VectorSearchResult(chunk = entry.chunk, similarity = sim)
                else null
            }
            .sortedByDescending { it.similarity }
            .take(topK)
    }

    override suspend fun deleteByDocument(userId: String, documentId: String) {
        store.keys.removeAll(
            store.values
                .filter { it.chunk.userId == userId && it.chunk.documentId == documentId }
                .map { it.chunk.chunkId }
                .toSet()
        )
    }

    override suspend fun deleteAll(userId: String) {
        store.keys.removeAll(
            store.values.filter { it.chunk.userId == userId }.map { it.chunk.chunkId }.toSet()
        )
    }

    override suspend fun count(userId: String): Int =
        store.values.count { it.chunk.userId == userId }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun chunk(
    id: String,
    docId: String = "doc1",
    userId: String = "u1",
    text: String = "sample text",
) = VectorChunk(
    chunkId = id,
    documentId = docId,
    documentName = "Doc",
    userId = userId,
    chunkIndex = 0,
    text = text,
)

private fun unitVec(dim: Int = 2, index: Int = 0): EmbeddingVector {
    val values = MutableList(dim) { 0f }
    values[index % dim] = 1f
    return EmbeddingVector(values = values)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class VectorStoreTest {

    private fun store() = InMemoryVectorStore()

    // ── VectorChunk ───────────────────────────────────────────────────────────

    @Test fun `VectorChunk valid construction`() {
        val c = chunk("c1")
        c.chunkId shouldBe "c1"
        c.pageNumber shouldBe null
    }

    @Test fun `VectorChunk blank chunkId raises`() {
        shouldThrow<IllegalArgumentException> {
            VectorChunk(chunkId = "  ", documentId = "d", documentName = "n",
                userId = "u", chunkIndex = 0, text = "t")
        }
    }

    @Test fun `VectorChunk blank text raises`() {
        shouldThrow<IllegalArgumentException> {
            VectorChunk(chunkId = "c1", documentId = "d", documentName = "n",
                userId = "u", chunkIndex = 0, text = "   ")
        }
    }

    @Test fun `VectorChunk blank userId raises`() {
        shouldThrow<IllegalArgumentException> {
            VectorChunk(chunkId = "c1", documentId = "d", documentName = "n",
                userId = "  ", chunkIndex = 0, text = "t")
        }
    }

    // ── VectorSearchResult ────────────────────────────────────────────────────

    @Test fun `VectorSearchResult valid construction`() {
        val r = VectorSearchResult(chunk = chunk("c1"), similarity = 0.9f)
        r.similarity shouldBe 0.9f
        r.retrievalPath shouldBe "ann"
    }

    @Test fun `VectorSearchResult similarity below zero raises`() {
        shouldThrow<IllegalArgumentException> {
            VectorSearchResult(chunk = chunk("c1"), similarity = -0.01f)
        }
    }

    @Test fun `VectorSearchResult similarity above one raises`() {
        shouldThrow<IllegalArgumentException> {
            VectorSearchResult(chunk = chunk("c1"), similarity = 1.001f)
        }
    }

    @Test fun `VectorSearchResult boundary similarities allowed`() {
        VectorSearchResult(chunk = chunk("c1"), similarity = 0f).similarity shouldBe 0f
        VectorSearchResult(chunk = chunk("c1"), similarity = 1f).similarity shouldBe 1f
    }

    // ── VectorStoreException ──────────────────────────────────────────────────

    @Test fun `VectorStoreException carries retryable flag`() {
        val e = VectorStoreException("timeout", retryable = true)
        e.retryable.shouldBeTrue()
        e.message shouldBe "timeout"
    }

    @Test fun `VectorStoreException not retryable by default`() {
        VectorStoreException("bad state").retryable.shouldBeFalse()
    }

    // ── Interface contract ────────────────────────────────────────────────────

    @Test fun `count is zero on empty store`() = runTest {
        store().count("u1") shouldBe 0
    }

    @Test fun `upsert increments count`() = runTest {
        val s = store()
        s.upsert(chunk("c1"), unitVec())
        s.upsert(chunk("c2"), unitVec())
        s.count("u1") shouldBe 2
    }

    @Test fun `upsert replaces chunk with same id`() = runTest {
        val s = store()
        s.upsert(chunk("c1", text = "original"), unitVec(index = 0))
        s.upsert(chunk("c1", text = "updated"),  unitVec(index = 0))
        s.count("u1") shouldBe 1
    }

    @Test fun `search returns results ordered by similarity descending`() = runTest {
        val s = store()
        // chunk aligned with index 0 (similarity 1 to [1,0])
        s.upsert(chunk("c-high"), unitVec(index = 0))
        // chunk aligned with index 1 (similarity 0 to [1,0])
        s.upsert(chunk("c-low"),  unitVec(index = 1))
        val query = unitVec(index = 0)
        val results = s.search("u1", query, topK = 2)
        results.first().chunk.chunkId shouldBe "c-high"
    }

    @Test fun `search respects topK`() = runTest {
        val s = store()
        repeat(5) { s.upsert(chunk("c$it"), unitVec()) }
        s.search("u1", unitVec(), topK = 3) shouldHaveSize 3
    }

    @Test fun `search respects minSimilarity threshold`() = runTest {
        val s = store()
        s.upsert(chunk("c-high"), unitVec(index = 0))
        s.upsert(chunk("c-low"),  unitVec(index = 1))
        // Query aligned with index 0: c-high gets sim=1, c-low gets sim=0
        val results = s.search("u1", unitVec(index = 0), topK = 5, minSimilarity = 0.5f)
        results.all { it.similarity >= 0.5f }.shouldBeTrue()
    }

    @Test fun `search does not return chunks from other users`() = runTest {
        val s = store()
        s.upsert(chunk("c1", userId = "alice"), unitVec())
        s.upsert(chunk("c2", userId = "bob"),   unitVec())
        val results = s.search("alice", unitVec(), topK = 10)
        results.all { it.chunk.userId == "alice" }.shouldBeTrue()
    }

    @Test fun `search on empty store returns empty list`() = runTest {
        store().search("u1", unitVec(), topK = 5).shouldBeEmpty()
    }

    @Test fun `deleteByDocument removes only matching document chunks`() = runTest {
        val s = store()
        s.upsert(chunk("c1", docId = "doc1"), unitVec())
        s.upsert(chunk("c2", docId = "doc2"), unitVec())
        s.deleteByDocument("u1", "doc1")
        s.count("u1") shouldBe 1
        s.search("u1", unitVec(), topK = 5).first().chunk.documentId shouldBe "doc2"
    }

    @Test fun `deleteAll removes all user chunks`() = runTest {
        val s = store()
        repeat(3) { s.upsert(chunk("c$it", userId = "u1"), unitVec()) }
        s.upsert(chunk("cx", userId = "other"), unitVec())
        s.deleteAll("u1")
        s.count("u1") shouldBe 0
        s.count("other") shouldBe 1
    }

    @Test fun `stub satisfies interface at compile time`() {
        val vs: VectorStore = InMemoryVectorStore()
        vs shouldBe vs  // compile-time check
    }
}

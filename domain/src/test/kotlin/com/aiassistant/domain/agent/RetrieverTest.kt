/*
 * Domain unit tests for Retriever interface and its value objects:
 * RetrievalResult, RetrievalCitation, RetrievalException.
 */
package com.aiassistant.domain.agent

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

// ── Minimal stub ──────────────────────────────────────────────────────────────

private class FakeRetriever(
    private val chunks: List<VectorChunk> = emptyList(),
    private val answer: String = "",
) : Retriever {

    private fun fakeResults(userId: String, topK: Int, docIds: List<String>?) =
        chunks
            .filter { it.userId == userId && (docIds == null || it.documentId in docIds) }
            .take(topK)
            .mapIndexed { i, c ->
                VectorSearchResult(
                    chunk = c,
                    similarity = 1f - i * 0.1f,
                    retrievalPath = "ann",
                )
            }

    override suspend fun retrieve(
        userId: String,
        query: String,
        topK: Int,
        documentIds: List<String>?,
    ): RetrievalResult = RetrievalResult(
        query = query,
        chunks = fakeResults(userId, topK, documentIds),
        citations = fakeResults(userId, topK, documentIds).map {
            RetrievalCitation(
                documentId = it.chunk.documentId,
                documentName = it.chunk.documentName,
                pageNumber = it.chunk.pageNumber,
                excerpt = it.chunk.text.take(50),
                similarity = it.similarity,
            )
        },
    )

    override suspend fun retrieveAndGenerate(
        userId: String,
        query: String,
        topK: Int,
        documentIds: List<String>?,
    ): RetrievalResult = retrieve(userId, query, topK, documentIds).copy(answer = answer)
}

// ── Helper ────────────────────────────────────────────────────────────────────

private fun fakeChunk(
    id: String,
    docId: String = "doc1",
    userId: String = "u1",
    text: String = "chunk text",
) = VectorChunk(
    chunkId = id, documentId = docId, documentName = "Doc",
    userId = userId, chunkIndex = 0, text = text,
)

// ── Tests ─────────────────────────────────────────────────────────────────────

class RetrieverTest {

    // ── RetrievalCitation ─────────────────────────────────────────────────────

    @Test fun `RetrievalCitation valid construction`() {
        val c = RetrievalCitation(documentId = "d1", documentName = "Report")
        c.documentId shouldBe "d1"
        c.pageNumber shouldBe null
        c.similarity shouldBe 0f
        c.retrievalPath shouldBe "ann"
    }

    @Test fun `RetrievalCitation stores all fields`() {
        val c = RetrievalCitation(
            documentId = "d1",
            documentName = "Report",
            pageNumber = 3,
            excerpt = "relevant text",
            similarity = 0.88f,
            retrievalPath = "bm25",
        )
        c.pageNumber shouldBe 3
        c.excerpt shouldBe "relevant text"
        c.similarity shouldBe 0.88f
        c.retrievalPath shouldBe "bm25"
    }

    // ── RetrievalResult ───────────────────────────────────────────────────────

    @Test fun `RetrievalResult hasResults false when chunks empty`() {
        RetrievalResult(query = "Q").hasResults.shouldBeFalse()
    }

    @Test fun `RetrievalResult hasResults true when chunks present`() {
        val chunk = fakeChunk("c1")
        val result = VectorSearchResult(chunk = chunk, similarity = 0.9f)
        RetrievalResult(query = "Q", chunks = listOf(result)).hasResults.shouldBeTrue()
    }

    @Test fun `RetrievalResult hasAnswer false when answer blank`() {
        RetrievalResult(query = "Q").hasAnswer.shouldBeFalse()
    }

    @Test fun `RetrievalResult hasAnswer true when answer present`() {
        RetrievalResult(query = "Q", answer = "The answer.").hasAnswer.shouldBeTrue()
    }

    // ── RetrievalException ────────────────────────────────────────────────────

    @Test fun `RetrievalException carries retryable flag`() {
        val e = RetrievalException("timeout", retryable = true)
        e.retryable.shouldBeTrue()
        e.message shouldBe "timeout"
    }

    @Test fun `RetrievalException not retryable by default`() {
        RetrievalException("bad state").retryable.shouldBeFalse()
    }

    // ── Interface contract ────────────────────────────────────────────────────

    @Test fun `retrieve returns empty result when no chunks`() = runTest {
        val r = FakeRetriever().retrieve("u1", "query")
        r.hasResults.shouldBeFalse()
        r.query shouldBe "query"
    }

    @Test fun `retrieve returns chunks for matching user`() = runTest {
        val chunks = listOf(fakeChunk("c1"), fakeChunk("c2"), fakeChunk("c3", userId = "other"))
        val r = FakeRetriever(chunks).retrieve("u1", "query", topK = 10)
        r.chunks shouldHaveSize 2
        r.chunks.all { it.chunk.userId == "u1" }.shouldBeTrue()
    }

    @Test fun `retrieve respects topK`() = runTest {
        val chunks = List(10) { fakeChunk("c$it") }
        val r = FakeRetriever(chunks).retrieve("u1", "query", topK = 3)
        r.chunks shouldHaveSize 3
    }

    @Test fun `retrieve filters by documentIds when provided`() = runTest {
        val chunks = listOf(
            fakeChunk("c1", docId = "doc1"),
            fakeChunk("c2", docId = "doc2"),
            fakeChunk("c3", docId = "doc3"),
        )
        val r = FakeRetriever(chunks).retrieve("u1", "q", documentIds = listOf("doc1", "doc3"))
        r.chunks shouldHaveSize 2
        r.chunks.all { it.chunk.documentId in listOf("doc1", "doc3") }.shouldBeTrue()
    }

    @Test fun `retrieve null documentIds searches all documents`() = runTest {
        val chunks = List(5) { fakeChunk("c$it", docId = "doc$it") }
        val r = FakeRetriever(chunks).retrieve("u1", "q", documentIds = null)
        r.chunks shouldHaveSize 5
    }

    @Test fun `retrieve includes citations`() = runTest {
        val r = FakeRetriever(listOf(fakeChunk("c1"))).retrieve("u1", "q")
        r.citations shouldHaveSize 1
        r.citations[0].documentId shouldBe "doc1"
    }

    @Test fun `retrieveAndGenerate answer is blank when no answer configured`() = runTest {
        val r = FakeRetriever(answer = "").retrieveAndGenerate("u1", "Q")
        r.hasAnswer.shouldBeFalse()
    }

    @Test fun `retrieveAndGenerate populates answer`() = runTest {
        val r = FakeRetriever(answer = "Because X.").retrieveAndGenerate("u1", "Why X?")
        r.answer shouldBe "Because X."
        r.hasAnswer.shouldBeTrue()
    }

    @Test fun `retrieveAndGenerate also includes chunks`() = runTest {
        val chunks = listOf(fakeChunk("c1"))
        val r = FakeRetriever(chunks, "answer").retrieveAndGenerate("u1", "q")
        r.hasResults.shouldBeTrue()
        r.hasAnswer.shouldBeTrue()
    }

    @Test fun `stub satisfies interface at compile time`() {
        val ret: Retriever = FakeRetriever()
        ret shouldBe ret
    }
}

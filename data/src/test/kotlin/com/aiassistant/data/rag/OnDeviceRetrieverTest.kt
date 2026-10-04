/*
 * Unit tests for OnDeviceRetriever.
 *
 * All dependencies (EmbeddingProvider, VectorStore, LlmClient) are faked.
 * No real model, no Room DB, no network, no production credentials.
 */
package com.aiassistant.data.rag

import com.aiassistant.domain.agent.EmbeddingException
import com.aiassistant.domain.agent.EmbeddingProvider
import com.aiassistant.domain.agent.EmbeddingVector
import com.aiassistant.domain.agent.LlmClient
import com.aiassistant.domain.agent.LlmClientException
import com.aiassistant.domain.agent.LlmRequest
import com.aiassistant.domain.agent.LlmResponse
import com.aiassistant.domain.agent.RetrievalException
import com.aiassistant.domain.agent.Retriever
import com.aiassistant.domain.agent.VectorChunk
import com.aiassistant.domain.agent.VectorSearchResult
import com.aiassistant.domain.agent.VectorStore
import com.aiassistant.domain.agent.VectorStoreException
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// ── Fakes ─────────────────────────────────────────────────────────────────────

private class FakeEmbeddingProvider(
    private val throwOnEmbed: Boolean = false,
    override val modelName: String = "test-model",
    override val embeddingDimension: Int = 4,
    override val isReady: Boolean = true,
) : EmbeddingProvider {
    var embedCallCount = 0
    var lastEmbeddedText: String = ""

    override suspend fun embed(text: String): EmbeddingVector {
        if (throwOnEmbed) throw EmbeddingException("embed failed", modelName)
        embedCallCount++
        lastEmbeddedText = text
        return EmbeddingVector(values = List(embeddingDimension) { 0.5f }, modelName = modelName)
    }

    override suspend fun embedBatch(texts: List<String>) = texts.map { embed(it) }
}

private open class FakeVectorStore(
    private val results: List<VectorSearchResult> = emptyList(),
    private val throwOnSearch: Boolean = false,
) : VectorStore {
    var searchCallCount = 0
    var lastUserId: String = ""
    var lastTopK: Int = 0
    var lastMinSimilarity: Float = 0f

    override suspend fun upsert(chunk: VectorChunk, embedding: EmbeddingVector) {}
    override suspend fun search(
        userId: String,
        queryEmbedding: EmbeddingVector,
        topK: Int,
        minSimilarity: Float,
    ): List<VectorSearchResult> {
        if (throwOnSearch) throw VectorStoreException("search failed")
        searchCallCount++
        lastUserId = userId
        lastTopK = topK
        lastMinSimilarity = minSimilarity
        return results
    }
    override suspend fun deleteByDocument(userId: String, documentId: String) {}
    override suspend fun deleteAll(userId: String) {}
    override suspend fun count(userId: String) = results.size
}

private class FakeLlmClient(
    private val answer: String = "The answer is 42.",
    private val throwOnGenerate: Boolean = false,
) : LlmClient {
    override val providerName = "fake"
    override val isAvailable = true
    var generateCallCount = 0
    var lastRequest: LlmRequest? = null

    override suspend fun generate(request: LlmRequest): LlmResponse {
        if (throwOnGenerate) throw LlmClientException("LLM failed", "fake")
        generateCallCount++
        lastRequest = request
        return LlmResponse(text = answer, provider = "fake")
    }

    override fun stream(request: LlmRequest): Flow<com.aiassistant.domain.agent.LlmEvent> = emptyFlow()
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private const val USER = "user-1"

private fun chunk(
    chunkId: String = "c1",
    documentId: String = "doc-1",
    documentName: String = "report.pdf",
    text: String = "Revenue grew 15%.",
    pageNumber: Int? = 3,
    charStart: Int = 0,
    charEnd: Int = 0,
) = VectorChunk(
    chunkId = chunkId,
    documentId = documentId,
    documentName = documentName,
    userId = USER,
    chunkIndex = 0,
    text = text,
    pageNumber = pageNumber,
    charStart = charStart,
    charEnd = charEnd,
)

private fun result(
    chunk: VectorChunk = chunk(),
    similarity: Float = 0.85f,
    path: String = "local_ann",
) = VectorSearchResult(chunk = chunk, similarity = similarity, retrievalPath = path)

private fun retriever(
    results: List<VectorSearchResult> = emptyList(),
    answer: String = "The answer.",
    throwEmbed: Boolean = false,
    throwSearch: Boolean = false,
    throwLlm: Boolean = false,
    config: RetrieverConfig = RetrieverConfig(),
) = OnDeviceRetriever(
    embeddingProvider = FakeEmbeddingProvider(throwOnEmbed = throwEmbed),
    vectorStore = FakeVectorStore(results = results, throwOnSearch = throwSearch),
    llmClient = FakeLlmClient(answer = answer, throwOnGenerate = throwLlm),
    config = config,
)

// ── RetrieverConfig ───────────────────────────────────────────────────────────

class RetrieverConfigTest {
    @Test fun `default topK is 5`() {
        RetrieverConfig().topK shouldBe 5
    }

    @Test fun `default minSimilarity is 0`() {
        RetrieverConfig().minSimilarity shouldBe 0f
    }

    @Test fun `topK below 1 raises`() {
        assertThrows<IllegalArgumentException> { RetrieverConfig(topK = 0) }
    }

    @Test fun `minSimilarity below 0 raises`() {
        assertThrows<IllegalArgumentException> { RetrieverConfig(minSimilarity = -0.1f) }
    }

    @Test fun `minSimilarity above 1 raises`() {
        assertThrows<IllegalArgumentException> { RetrieverConfig(minSimilarity = 1.1f) }
    }
}

// ── Retriever interface contract ──────────────────────────────────────────────

class OnDeviceRetrieverInterfaceTest {
    @Test fun `implements Retriever interface`() {
        val r: Retriever = retriever()
        r shouldNotBe null
    }
}

// ── retrieve() ───────────────────────────────────────────────────────────────

class OnDeviceRetrieverRetrieveTest {

    @Test fun `blank query returns empty result`() = runTest {
        val result = retriever().retrieve(USER, "   ")
        result.hasResults.shouldBeFalse()
    }

    @Test fun `empty query returns empty result`() = runTest {
        val result = retriever().retrieve(USER, "")
        result.hasResults.shouldBeFalse()
    }

    @Test fun `calls embed with the query`() = runTest {
        val embed = FakeEmbeddingProvider()
        val r = OnDeviceRetriever(embed, FakeVectorStore(), FakeLlmClient())
        r.retrieve(USER, "what is revenue?")
        embed.lastEmbeddedText shouldBe "what is revenue?"
    }

    @Test fun `calls vector store search`() = runTest {
        val store = FakeVectorStore(results = listOf(result()))
        val r = OnDeviceRetriever(FakeEmbeddingProvider(), store, FakeLlmClient())
        r.retrieve(USER, "query")
        store.searchCallCount shouldBe 1
    }

    @Test fun `passes userId to vector store`() = runTest {
        val store = FakeVectorStore()
        val r = OnDeviceRetriever(FakeEmbeddingProvider(), store, FakeLlmClient())
        r.retrieve("alice", "query")
        store.lastUserId shouldBe "alice"
    }

    @Test fun `passes topK from config`() = runTest {
        val store = FakeVectorStore()
        val r = OnDeviceRetriever(FakeEmbeddingProvider(), store, FakeLlmClient(), RetrieverConfig(topK = 7))
        // Pass topK explicitly matching the config to verify it flows through
        r.retrieve(USER, "query", topK = 7)
        store.lastTopK shouldBe 7
    }

    @Test fun `passes minSimilarity from config`() = runTest {
        val store = FakeVectorStore()
        val r = OnDeviceRetriever(FakeEmbeddingProvider(), store, FakeLlmClient(), RetrieverConfig(minSimilarity = 0.6f))
        r.retrieve(USER, "query")
        store.lastMinSimilarity shouldBe 0.6f
    }

    @Test fun `returns chunks from vector store`() = runTest {
        val results = listOf(result(), result(chunk(chunkId = "c2")))
        val result = retriever(results = results).retrieve(USER, "query")
        result.chunks shouldHaveSize 2
        result.hasResults.shouldBeTrue()
    }

    @Test fun `no chunks returns empty result`() = runTest {
        val r = retriever(results = emptyList()).retrieve(USER, "query")
        r.hasResults.shouldBeFalse()
    }

    @Test fun `answer is empty in retrieve-only`() = runTest {
        val r = retriever(results = listOf(result())).retrieve(USER, "query")
        r.answer shouldBe ""
    }

    @Test fun `citations populated in retrieve`() = runTest {
        val r = retriever(results = listOf(result())).retrieve(USER, "query")
        r.citations shouldHaveSize 1
    }

    @Test fun `citation has documentName`() = runTest {
        val c = chunk(documentName = "annual.pdf")
        val r = retriever(results = listOf(result(c))).retrieve(USER, "query")
        r.citations[0].documentName shouldBe "annual.pdf"
    }

    @Test fun `citation has pageNumber`() = runTest {
        val c = chunk(pageNumber = 5)
        val r = retriever(results = listOf(result(c))).retrieve(USER, "query")
        r.citations[0].pageNumber shouldBe 5
    }

    @Test fun `citation has similarity`() = runTest {
        val r = retriever(results = listOf(result(similarity = 0.77f))).retrieve(USER, "query")
        r.citations[0].similarity shouldBe 0.77f
    }

    @Test fun `citation has retrievalPath`() = runTest {
        val r = retriever(results = listOf(result(path = "local_ann"))).retrieve(USER, "query")
        r.citations[0].retrievalPath shouldBe "local_ann"
    }

    @Test fun `document-level filter excludes non-matching chunks`() = runTest {
        val results = listOf(
            result(chunk(chunkId = "c1", documentId = "doc-A")),
            result(chunk(chunkId = "c2", documentId = "doc-B")),
        )
        val r = retriever(results = results).retrieve(USER, "query", documentIds = listOf("doc-A"))
        r.chunks shouldHaveSize 1
        r.chunks[0].chunk.documentId shouldBe "doc-A"
    }

    @Test fun `null documentIds returns all chunks`() = runTest {
        val results = listOf(result(), result(chunk(chunkId = "c2", documentId = "doc-B")))
        val r = retriever(results = results).retrieve(USER, "query", documentIds = null)
        r.chunks shouldHaveSize 2
    }

    @Test fun `embedding failure returns empty result gracefully`() = runTest {
        val r = retriever(throwEmbed = true).retrieve(USER, "query")
        r.hasResults.shouldBeFalse()
    }

    @Test fun `embedding failure reraised when configured`() = runTest {
        val r = OnDeviceRetriever(
            FakeEmbeddingProvider(throwOnEmbed = true),
            FakeVectorStore(),
            FakeLlmClient(),
            RetrieverConfig(reraiseErrors = true),
        )
        assertThrows<RetrievalException> { r.retrieve(USER, "query") }
    }

    @Test fun `search failure returns empty result gracefully`() = runTest {
        val r = retriever(throwSearch = true).retrieve(USER, "query")
        r.hasResults.shouldBeFalse()
    }

    @Test fun `search failure reraised when configured`() = runTest {
        val r = OnDeviceRetriever(
            FakeEmbeddingProvider(),
            FakeVectorStore(throwOnSearch = true),
            FakeLlmClient(),
            RetrieverConfig(reraiseErrors = true),
        )
        assertThrows<RetrievalException> { r.retrieve(USER, "query") }
    }
}

// ── retrieveAndGenerate() ────────────────────────────────────────────────────

class OnDeviceRetrieverGenerateTest {

    @Test fun `no chunks returns no-results answer without calling LLM`() = runTest {
        val llm = FakeLlmClient()
        val r = OnDeviceRetriever(FakeEmbeddingProvider(), FakeVectorStore(), llm)
        val result = r.retrieveAndGenerate(USER, "Q?")
        llm.generateCallCount shouldBe 0
        result.answer shouldContain OnDeviceRetriever.NO_RESULTS_ANSWER.take(10)
    }

    @Test fun `answer populated from LLM`() = runTest {
        val result = retriever(results = listOf(result()), answer = "42 is the answer.").retrieveAndGenerate(USER, "Q?")
        result.answer shouldBe "42 is the answer."
    }

    @Test fun `hasAnswer true when LLM responds`() = runTest {
        val result = retriever(results = listOf(result()), answer = "answer").retrieveAndGenerate(USER, "Q?")
        result.hasAnswer.shouldBeTrue()
    }

    @Test fun `chunks still present in result`() = runTest {
        val chunks = listOf(result(), result(chunk(chunkId = "c2")))
        val r = retriever(results = chunks).retrieveAndGenerate(USER, "Q?")
        r.chunks shouldHaveSize 2
    }

    @Test fun `citations present in result`() = runTest {
        val r = retriever(results = listOf(result())).retrieveAndGenerate(USER, "Q?")
        r.citations shouldHaveSize 1
    }

    @Test fun `LLM receives chunk text in prompt`() = runTest {
        val c = chunk(text = "unique fact xyz")
        val llm = FakeLlmClient()
        val r = OnDeviceRetriever(FakeEmbeddingProvider(), FakeVectorStore(listOf(result(c))), llm)
        r.retrieveAndGenerate(USER, "Q?")
        llm.lastRequest?.prompt shouldContain "unique fact xyz"
    }

    @Test fun `LLM receives the user question`() = runTest {
        val llm = FakeLlmClient()
        val r = OnDeviceRetriever(FakeEmbeddingProvider(), FakeVectorStore(listOf(result())), llm)
        r.retrieveAndGenerate(USER, "What is the revenue?")
        llm.lastRequest?.prompt shouldContain "What is the revenue?"
    }

    @Test fun `LLM receives non-empty systemPrompt`() = runTest {
        val llm = FakeLlmClient()
        val r = OnDeviceRetriever(FakeEmbeddingProvider(), FakeVectorStore(listOf(result())), llm)
        r.retrieveAndGenerate(USER, "Q?")
        (llm.lastRequest?.systemPrompt?.isNotBlank() ?: false).shouldBeTrue()
    }

    @Test fun `LLM receives ragContext list`() = runTest {
        val c = chunk(text = "fact in context")
        val llm = FakeLlmClient()
        val r = OnDeviceRetriever(FakeEmbeddingProvider(), FakeVectorStore(listOf(result(c))), llm)
        r.retrieveAndGenerate(USER, "Q?")
        (llm.lastRequest?.ragContext?.isNotEmpty() ?: false).shouldBeTrue()
        (llm.lastRequest?.ragContext?.any { "fact in context" in it } ?: false).shouldBeTrue()
    }

    @Test fun `LLM failure returns partial result with chunks`() = runTest {
        val result = retriever(results = listOf(result()), throwLlm = true).retrieveAndGenerate(USER, "Q?")
        result.hasResults.shouldBeTrue()
        result.answer shouldContain OnDeviceRetriever.LLM_FAILURE_ANSWER.take(10)
    }

    @Test fun `page citation tag included in context`() = runTest {
        val c = chunk(documentName = "report.pdf", pageNumber = 7)
        val llm = FakeLlmClient()
        val r = OnDeviceRetriever(FakeEmbeddingProvider(), FakeVectorStore(listOf(result(c))), llm)
        r.retrieveAndGenerate(USER, "Q?")
        llm.lastRequest?.prompt shouldContain "report.pdf"
        llm.lastRequest?.prompt shouldContain "Page 7"
    }

    @Test fun `char offset citation tag when no page`() = runTest {
        val c = chunk(documentName = "notes.md", pageNumber = null, charStart = 10, charEnd = 200)
        val llm = FakeLlmClient()
        val r = OnDeviceRetriever(FakeEmbeddingProvider(), FakeVectorStore(listOf(result(c))), llm)
        r.retrieveAndGenerate(USER, "Q?")
        llm.lastRequest?.prompt shouldContain "Chars 10-200"
    }
}

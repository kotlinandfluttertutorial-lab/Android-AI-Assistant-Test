/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : OnDeviceRetriever.kt
 * Purpose    : On-device implementation of the domain [Retriever] port.
 *              Composes [EmbeddingProvider] (on-device MiniLM) and
 *              [VectorStore] (Room-backed LocalVectorStoreAdapter) to
 *              perform semantic retrieval, then optionally calls an
 *              [LlmClient] to generate a grounded answer.
 *
 * Architecture Layer : Data — rag
 * Pattern Used       : Adapter / Composite
 *
 * Flow:
 *   retrieve():
 *     1. embed(query)  via EmbeddingProvider
 *     2. search(userId, embedding, topK, minSimilarity)  via VectorStore
 *     3. map results → RetrievalResult (no LLM call)
 *
 *   retrieveAndGenerate():
 *     1–3 same as retrieve()
 *     4. build numbered context block from retrieved chunks
 *     5. call LlmClient.generate(LlmRequest(prompt=context+question))
 *     6. return RetrievalResult(answer=..., citations=[...])
 *
 * Error handling:
 *   - Empty query → empty RetrievalResult immediately.
 *   - EmbeddingException → RetrievalException (retryable=false).
 *   - VectorStoreException → empty result (graceful degradation) unless
 *     reraise = true.
 *   - LlmClientException → partial result with error message as answer.
 *   - No chunks found → "no information found" answer without LLM call.
 *
 * Design rules:
 *   - Pure Kotlin, zero Android framework dependency in core logic.
 *   - No direct dependency on Room, chromadb, sentence-transformers.
 *   - Injected via Hilt (binding declared in a future OnDeviceRagModule).
 * ============================================================
 */

package com.aiassistant.data.rag

import com.aiassistant.domain.agent.EmbeddingException
import com.aiassistant.domain.agent.EmbeddingProvider
import com.aiassistant.domain.agent.LlmClient
import com.aiassistant.domain.agent.LlmClientException
import com.aiassistant.domain.agent.LlmRequest
import com.aiassistant.domain.agent.RetrievalCitation
import com.aiassistant.domain.agent.RetrievalException
import com.aiassistant.domain.agent.RetrievalResult
import com.aiassistant.domain.agent.Retriever
import com.aiassistant.domain.agent.VectorSearchResult
import com.aiassistant.domain.agent.VectorStore
import com.aiassistant.domain.agent.VectorStoreException
import javax.inject.Inject

/**
 * On-device [Retriever] implementation backed by local embedding and vector storage.
 *
 * All retrieval happens on-device with zero network calls.  LLM generation uses
 * the provided [LlmClient] which may delegate to the on-device Gemma engine or
 * a cloud provider depending on the Hilt binding in the app module.
 *
 * @param embeddingProvider  Provides dense embeddings (injected).
 * @param vectorStore        Stores and searches chunk embeddings (injected).
 * @param llmClient          Generates answers from retrieved context (injected).
 * @param config             Retrieval parameters (top-K, similarity threshold, etc.).
 */
class OnDeviceRetriever @Inject constructor(
    private val embeddingProvider: EmbeddingProvider,
    private val vectorStore: VectorStore,
    private val llmClient: LlmClient,
    private val config: RetrieverConfig = RetrieverConfig(),
) : Retriever {

    // ── Retriever interface ───────────────────────────────────────────────────

    /**
     * Embed [query] and search the on-device vector store.
     *
     * Does NOT call the LLM.  [RetrievalResult.answer] is empty; only
     * [RetrievalResult.chunks] and [RetrievalResult.citations] are populated.
     *
     * @throws RetrievalException if embedding fails and [RetrieverConfig.reraiseErrors] is true.
     */
    override suspend fun retrieve(
        userId: String,
        query: String,
        topK: Int,
        documentIds: List<String>?,
    ): RetrievalResult {
        if (query.isBlank()) {
            return RetrievalResult(query = query)
        }

        // Step 1: Embed the query
        val queryEmbedding = try {
            embeddingProvider.embed(query)
        } catch (e: EmbeddingException) {
            if (config.reraiseErrors) throw RetrievalException(
                message = "Embedding failed: ${e.message}",
                retryable = e.retryable,
                cause = e,
            )
            return RetrievalResult(query = query)
        } catch (e: Exception) {
            if (config.reraiseErrors) throw RetrievalException(
                message = "Unexpected embedding error: ${e.message}",
                retryable = false,
                cause = e,
            )
            return RetrievalResult(query = query)
        }

        // Step 2: Search
        val rawResults: List<VectorSearchResult> = try {
            vectorStore.search(
                userId = userId,
                queryEmbedding = queryEmbedding,
                topK = topK,
                minSimilarity = config.minSimilarity,
            ).let { results ->
                // Optional document-level filter (VectorStore may not support it natively)
                if (documentIds.isNullOrEmpty()) results
                else results.filter { it.chunk.documentId in documentIds }
            }
        } catch (e: VectorStoreException) {
            if (config.reraiseErrors) throw RetrievalException(
                message = "Vector search failed: ${e.message}",
                retryable = e.retryable,
                cause = e,
            )
            return RetrievalResult(query = query)
        }

        if (rawResults.isEmpty()) {
            return RetrievalResult(query = query)
        }

        val citations = rawResults.map { it.toCitation() }

        return RetrievalResult(
            query = query,
            chunks = rawResults,
            answer = "",
            citations = citations,
        )
    }

    /**
     * Retrieve relevant chunks AND generate a grounded answer.
     *
     * If no chunks are found the LLM is NOT called and a "no information found"
     * message is returned.  If the LLM fails the chunks are still returned with
     * an error answer so the caller can render citations.
     *
     * @throws RetrievalException if embedding fails and [RetrieverConfig.reraiseErrors] is true.
     */
    override suspend fun retrieveAndGenerate(
        userId: String,
        query: String,
        topK: Int,
        documentIds: List<String>?,
    ): RetrievalResult {
        val retrieval = retrieve(userId, query, topK, documentIds)

        if (!retrieval.hasResults) {
            return RetrievalResult(
                query = query,
                chunks = emptyList(),
                answer = NO_RESULTS_ANSWER,
                citations = emptyList(),
            )
        }

        // Build context block and citations
        val contextBlock = buildContextBlock(retrieval.chunks)
        val fullPrompt = contextBlock + QUESTION_SEPARATOR + query
        val systemPrompt = RAG_SYSTEM_PROMPT

        // Generate answer
        val answer: String = try {
            val request = LlmRequest(
                prompt = fullPrompt,
                systemPrompt = systemPrompt,
                userId = userId,
                ragContext = retrieval.chunks.map { it.chunk.text },
            )
            llmClient.generate(request).text
        } catch (e: LlmClientException) {
            LLM_FAILURE_ANSWER
        } catch (e: Exception) {
            LLM_FAILURE_ANSWER
        }

        return RetrievalResult(
            query = query,
            chunks = retrieval.chunks,
            answer = answer,
            citations = retrieval.citations,
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildContextBlock(chunks: List<VectorSearchResult>): String {
        val sb = StringBuilder("Retrieved Context:\n\n")
        chunks.forEachIndexed { i, result ->
            val chunk = result.chunk
            val citation = citationTag(chunk.documentName, chunk.pageNumber, chunk.charStart, chunk.charEnd)
            sb.append("--- Chunk ${i + 1} $citation ---\n")
            sb.append(chunk.text)
            sb.append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    private fun VectorSearchResult.toCitation(): RetrievalCitation = RetrievalCitation(
        documentId = chunk.documentId,
        documentName = chunk.documentName,
        pageNumber = chunk.pageNumber,
        excerpt = chunk.text.take(200),
        similarity = similarity,
        retrievalPath = retrievalPath,
    )

    companion object {
        private const val QUESTION_SEPARATOR = "\n\n---\n\nQuestion: "

        private val RAG_SYSTEM_PROMPT = """
            You are a helpful assistant. Answer the question using ONLY the information
            in the provided context. If the context does not contain enough information
            to answer the question, say so clearly and do not invent facts.
            When referencing context, cite sources using the inline tags shown.
        """.trimIndent()

        internal const val NO_RESULTS_ANSWER =
            "I could not find any relevant information in your documents to answer this question."

        internal const val LLM_FAILURE_ANSWER =
            "I retrieved relevant context but was unable to generate an answer due to a service error. Please try again."

        private fun citationTag(
            documentName: String,
            pageNumber: Int?,
            charStart: Int,
            charEnd: Int,
        ): String = when {
            pageNumber != null -> "[Source: $documentName, Page $pageNumber]"
            charStart > 0 || charEnd > 0 -> "[Source: $documentName, Chars $charStart-$charEnd]"
            else -> "[Source: $documentName]"
        }
    }
}

/**
 * Configuration for [OnDeviceRetriever].
 *
 * @param topK           Maximum chunks to retrieve (default 5).
 * @param minSimilarity  Minimum cosine similarity (0.0–1.0). Default 0.0 = no filter.
 * @param reraiseErrors  When true, embedding/search errors propagate as
 *                       [RetrievalException] instead of returning empty results.
 */
data class RetrieverConfig(
    val topK: Int = 5,
    val minSimilarity: Float = 0.0f,
    val reraiseErrors: Boolean = false,
) {
    init {
        require(topK >= 1) { "RetrieverConfig.topK must be ≥ 1, got $topK." }
        require(minSimilarity in 0f..1f) {
            "RetrieverConfig.minSimilarity must be in [0.0, 1.0], got $minSimilarity."
        }
    }
}

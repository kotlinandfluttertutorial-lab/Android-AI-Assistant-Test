/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : OnDeviceQueryUseCase.kt
 * Purpose    : Executes an on-device RAG query: embed → search → build context
 *              → stream Gemma generation.  Gemma receives only the assembled
 *              context string — it never calls embed or search directly
 *              (Property 41 / Requirement 35.7).
 *
 * Architecture Layer : Domain — pure Kotlin use case.
 *
 * Design Decision    : The use case assembles the RAG context string from
 *                      retrieved chunks before passing it to the inference
 *                      engine.  This keeps the generation engine strictly
 *                      generation-only and makes the boundary testable
 *                      (Property 41 spy test verifies no embed/search calls
 *                      appear on the engine).
 *
 * Requirements: 35.1, 35.4, 35.5, 35.7, 35.8, 35.9, 36.5, 36.6, 36.7, 36.8
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.ai.ondevicerag.LocalVectorIndex
import com.aiassistant.core.ai.ondevicerag.OnDeviceEmbeddingModel
import com.aiassistant.core.ai.ondevicerag.OnDeviceInferenceEngine
import com.aiassistant.core.ai.ondevicerag.OnDeviceStreamEvent
import com.aiassistant.domain.model.ChunkCitation
import com.aiassistant.domain.model.OnDeviceQueryEvent
import com.aiassistant.domain.repository.QueryMetricsRepository
import com.aiassistant.domain.repository.QueryMetricsSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** Minimum cosine similarity for a chunk to be included in the RAG context. */
private const val MIN_SIMILARITY = 0.40f

/** Number of top chunks to retrieve. */
private const val TOP_K = 5

/** Maximum characters from a chunk included in the citation excerpt. */
private const val MAX_EXCERPT_CHARS = 200

/**
 * Executes a RAG query entirely on-device.
 *
 * Pipeline:
 * 1. Embed [query] via [embeddingModel].
 * 2. Search [vectorIndex] for the top-[TOP_K] chunks above [MIN_SIMILARITY].
 * 3. If no results → emit [OnDeviceQueryEvent.NoRelevantContent].
 * 4. Assemble RAG prompt (context chunks + query).
 * 5. Stream tokens from [inferenceEngine.generateStream] — emitting [OnDeviceQueryEvent.Token].
 * 6. On completion emit [OnDeviceQueryEvent.Done] with citation list.
 * 7. Record performance metrics via [metricsRepository].
 *
 * @param embeddingModel    Converts the query text to a float32 vector.
 * @param vectorIndex       Retrieves the most semantically relevant chunks.
 * @param inferenceEngine   Gemma generation engine — generation only, never embedding.
 * @param metricsRepository Records TTFT/throughput/RAM metrics for BenchmarkScreen.
 */
class OnDeviceQueryUseCase @Inject constructor(
    private val embeddingModel: OnDeviceEmbeddingModel,
    private val vectorIndex: LocalVectorIndex,
    private val inferenceEngine: OnDeviceInferenceEngine,
    private val metricsRepository: QueryMetricsRepository,
) {

    /**
     * Runs the full on-device RAG query pipeline.
     *
     * @param query  The user's natural language question.
     * @param userId Owner whose vector index to search.
     * @param topK   Maximum number of chunks to retrieve (default [TOP_K]).
     * @return Cold [Flow] of [OnDeviceQueryEvent] events.
     */
    operator fun invoke(
        query: String,
        userId: String,
        topK: Int = TOP_K,
    ): Flow<OnDeviceQueryEvent> = flow {

        val startMs = System.currentTimeMillis()
        emit(OnDeviceQueryEvent.Searching)

        // ── 1. Embed query ─────────────────────────────────────────────────
        if (!embeddingModel.isReady) {
            emit(OnDeviceQueryEvent.Error("Embedding model not ready.", "embedding"))
            return@flow
        }
        val queryEmbedding = try {
            embeddingModel.generateEmbedding(query)
        } catch (e: Exception) {
            emit(OnDeviceQueryEvent.Error("Failed to embed query: ${e.message}", "embedding"))
            return@flow
        }

        // ── 2. Search vector index ─────────────────────────────────────────
        val results = try {
            vectorIndex.search(userId, queryEmbedding, topK, MIN_SIMILARITY)
        } catch (e: Exception) {
            emit(OnDeviceQueryEvent.Error("Vector search failed: ${e.message}", "search"))
            return@flow
        }

        if (results.isEmpty()) {
            emit(OnDeviceQueryEvent.NoRelevantContent)
            return@flow
        }

        // ── 3. Build RAG prompt (context + question) ───────────────────────
        // Gemma receives only this assembled string — it never calls embed/search.
        val contextBlock = results.joinToString("\n\n---\n\n") { r ->
            "[Source: ${r.chunk.documentName}, chunk ${r.chunk.chunkIndex}]\n${r.chunk.content}"
        }
        val prompt = buildRagPrompt(contextBlock, query)

        // ── 4. Stream generation ───────────────────────────────────────────
        var tokenCount = 0
        var firstTokenMs = -1L

        inferenceEngine.generateStream(prompt).collect { event ->
            when (event) {
                is OnDeviceStreamEvent.Token -> {
                    if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - startMs
                    tokenCount++
                    emit(OnDeviceQueryEvent.Token(event.text))
                }
                is OnDeviceStreamEvent.Done -> {
                    val citations = results.map { r ->
                        ChunkCitation(
                            documentId = r.chunk.documentId,
                            documentName = r.chunk.documentName,
                            chunkIndex = r.chunk.chunkIndex,
                            pageNumber = r.chunk.pageNumber,
                            excerpt = r.chunk.content.take(MAX_EXCERPT_CHARS),
                            cosineSimilarity = r.cosineSimilarity,
                        )
                    }
                    emit(
                        OnDeviceQueryEvent.Done(
                            tokensGenerated = event.tokensGenerated,
                            generationTimeMs = event.generationTimeMs,
                            citations = citations,
                        )
                    )
                    // Record metrics (fire-and-forget — don't let a metrics failure
                    // surface as a query error)
                    runCatching {
                        metricsRepository.recordSample(
                            QueryMetricsSample(
                                userId = userId,
                                timestampMs = System.currentTimeMillis(),
                                ttftMs = firstTokenMs.coerceAtLeast(0),
                                tokensGenerated = event.tokensGenerated,
                                generationTimeMs = event.generationTimeMs,
                                peakRamMb = 0, // populated by data layer if available
                                accelerator = inferenceEngine.activeAccelerator().name,
                            )
                        )
                    }
                }
                is OnDeviceStreamEvent.Error -> {
                    emit(OnDeviceQueryEvent.Error(event.message, event.stage))
                }
                is OnDeviceStreamEvent.Cancelled -> {
                    emit(OnDeviceQueryEvent.Error("Generation cancelled.", "generation"))
                }
            }
        }
    }

    private fun buildRagPrompt(context: String, question: String): String = """
        You are a helpful assistant. Answer the question using ONLY the context provided below.
        If the context does not contain enough information, say so.

        CONTEXT:
        $context

        QUESTION:
        $question

        ANSWER:
    """.trimIndent()
}

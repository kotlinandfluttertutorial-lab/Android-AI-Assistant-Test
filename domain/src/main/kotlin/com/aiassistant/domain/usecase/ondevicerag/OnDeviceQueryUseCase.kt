/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : OnDeviceQueryUseCase.kt
 * Purpose    : Executes an on-device RAG query: embed → search → build context
 *              → stream Gemma generation.
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.common.LocalVectorIndex
import com.aiassistant.core.common.ModelLoadEvent
import com.aiassistant.core.common.OnDeviceEmbeddingModel
import com.aiassistant.core.common.OnDeviceInferenceEngine
import com.aiassistant.core.common.OnDeviceStreamEvent
import com.aiassistant.domain.model.ChunkCitation
import com.aiassistant.domain.model.OnDeviceQueryEvent
import com.aiassistant.domain.repository.QueryMetricsRepository
import com.aiassistant.domain.repository.QueryMetricsSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

private const val MIN_SIMILARITY = 0.40f
private const val TOP_K = 5
private const val MAX_EXCERPT_CHARS = 200

class OnDeviceQueryUseCase @Inject constructor(
    private val embeddingModel: OnDeviceEmbeddingModel,
    private val vectorIndex: LocalVectorIndex,
    private val inferenceEngine: OnDeviceInferenceEngine,
    private val metricsRepository: QueryMetricsRepository,
) {

    operator fun invoke(
        query: String,
        userId: String,
        topK: Int = TOP_K,
    ): Flow<OnDeviceQueryEvent> = flow {
        val startMs = System.currentTimeMillis()
        emit(OnDeviceQueryEvent.Searching)

        val queryEmbedding = performEmbedding(query) ?: return@flow
        val results = performSearch(userId, queryEmbedding, topK) ?: return@flow

        if (results.isEmpty()) {
            emit(OnDeviceQueryEvent.NoRelevantContent)
            return@flow
        }

        val prompt = buildRagPrompt(
            context = results.joinToString("\n\n---\n\n") { "[Source: document ${it.documentId}]\n${it.content}" },
            question = query
        )

        var firstTokenMs = -1L
        inferenceEngine.generateStream(prompt).collect { event ->
            handleInferenceEvent(event, results, userId, startMs, firstTokenMs) {
                firstTokenMs = it
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun kotlinx.coroutines.flow.FlowCollector<OnDeviceQueryEvent>.performEmbedding(
        query: String
    ): FloatArray? = try {
        embeddingModel.generateEmbedding(query)
    } catch (e: Exception) {
        emit(OnDeviceQueryEvent.Error("Failed to embed query: ${e.message}", "embedding"))
        null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun kotlinx.coroutines.flow.FlowCollector<OnDeviceQueryEvent>.performSearch(
        userId: String,
        embedding: FloatArray,
        topK: Int
    ): List<com.aiassistant.core.common.ChunkSearchResult>? = try {
        vectorIndex.search(userId, embedding, topK, MIN_SIMILARITY)
    } catch (e: Exception) {
        emit(OnDeviceQueryEvent.Error("Vector search failed: ${e.message}", "search"))
        null
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<OnDeviceQueryEvent>.handleInferenceEvent(
        event: OnDeviceStreamEvent,
        results: List<com.aiassistant.core.common.ChunkSearchResult>,
        userId: String,
        startMs: Long,
        firstTokenMs: Long,
        onFirstToken: (Long) -> Unit
    ) {
        when (event) {
            is OnDeviceStreamEvent.Token -> {
                if (firstTokenMs < 0) onFirstToken(System.currentTimeMillis() - startMs)
                emit(OnDeviceQueryEvent.Token(event.text))
            }
            is OnDeviceStreamEvent.Done -> emitDone(event, results, userId, firstTokenMs)
            is OnDeviceStreamEvent.Error -> emit(OnDeviceQueryEvent.Error(event.message, event.stage))
            is OnDeviceStreamEvent.Cancelled -> emit(OnDeviceQueryEvent.Error("Generation cancelled.", "generation"))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<OnDeviceQueryEvent>.emitDone(
        event: OnDeviceStreamEvent.Done,
        results: List<com.aiassistant.core.common.ChunkSearchResult>,
        userId: String,
        firstTokenMs: Long
    ) {
        val citations = results.map { r ->
            ChunkCitation(
                documentId = r.documentId,
                documentName = "Document ${r.documentId}",
                chunkIndex = 0,
                pageNumber = null,
                excerpt = r.content.take(MAX_EXCERPT_CHARS),
                cosineSimilarity = r.cosineSimilarity,
            )
        }
        emit(OnDeviceQueryEvent.Done(event.tokensGenerated, event.generationTimeMs, citations))
        recordMetrics(userId, firstTokenMs, event)
    }

    private suspend fun recordMetrics(userId: String, firstTokenMs: Long, event: OnDeviceStreamEvent.Done) {
        runCatching {
            metricsRepository.recordSample(
                QueryMetricsSample(
                    userId = userId,
                    timestampMs = System.currentTimeMillis(),
                    ttftMs = firstTokenMs.coerceAtLeast(0),
                    tokensGenerated = event.tokensGenerated,
                    generationTimeMs = event.generationTimeMs,
                    peakRamMb = 0,
                    accelerator = inferenceEngine.activeAccelerator().name,
                )
            )
        }
    }

    private fun buildRagPrompt(context: String, question: String): String = """
        Use ONLY the context provided below.
        CONTEXT:
        $context
        QUESTION:
        $question
        ANSWER:
    """.trimIndent()
}

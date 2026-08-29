/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-common
 * File       : RagContracts.kt
 * Purpose    : Common interfaces and data classes for the on-device RAG pipeline.
 *              Relocated from core-ai to allow the domain module to access
 *              these contracts without violating architectural dependency rules.
 * ============================================================
 */
package com.aiassistant.core.common

import kotlinx.coroutines.flow.Flow

// ── Model Load Events ────────────────────────────────────────────────────────

/** Events produced by model initialization. */
sealed class ModelLoadEvent {
    /** Model loaded and checksum verified successfully. */
    data object Ready : ModelLoadEvent()

    /**
     * Model failed to load.
     *
     * @param reason Human-readable failure description.
     * @param cause  Underlying exception, if any.
     */
    data class Failed(val reason: String, val cause: Throwable? = null) : ModelLoadEvent()
}

// ── Accelerator enum ─────────────────────────────────────────────────────────

/** Hardware accelerator used by the inference engine. */
enum class HardwareAccelerator { CPU, GPU, NPU }

// ── Sealed event types ───────────────────────────────────────────────────────

/** Events emitted by [OnDeviceInferenceEngine.generateStream]. */
sealed class OnDeviceStreamEvent {
    /** One generated text token. */
    data class Token(val text: String) : OnDeviceStreamEvent()

    /**
     * Generation completed successfully.
     *
     * @param tokensGenerated   Number of tokens produced.
     * @param generationTimeMs  Wall-clock time from first token to Done.
     */
    data class Done(
        val tokensGenerated: Int,
        val generationTimeMs: Long,
    ) : OnDeviceStreamEvent()

    /**
     * Generation failed or was interrupted.
     *
     * @param message Human-readable description.
     * @param stage   Which lifecycle stage failed.
     */
    data class Error(
        val message: String,
        val stage: String,
    ) : OnDeviceStreamEvent()

    /** Generation was cancelled. */
    data object Cancelled : OnDeviceStreamEvent()
}

// ── BenchmarkResult ──────────────────────────────────────────────────────────

/** Result of an inference benchmark run. */
data class BenchmarkResult(
    val accelerator: HardwareAccelerator,
    val ttftMeanMs: Long,
    val ttftP95Ms: Long,
    val tokensPerSecMean: Float,
    val tokensPerSecP95: Float,
    val peakRamMb: Int,
)

// ── Chunker Types ────────────────────────────────────────────────────────────

/** Represents one page boundary within a document. */
data class PageOffset(
    val pageNumber: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
)

/** One chunk of text produced by [Chunker], ready for embedding. */
data class TextChunk(
    val id: String,
    val documentId: String,
    val documentName: String,
    val chunkIndex: Int,
    val pageNumber: Int?,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val content: String,
)

/** Splits document text into overlapping [TextChunk] objects. */
class Chunker(
    val chunkSizeTokens: Int = 512,
    val overlapTokens: Int = 64,
    val minChunkSizeTokens: Int = 64,
    val maxChunkSizeTokens: Int = 2048,
) {
    init {
        require(overlapTokens <= chunkSizeTokens / 2) {
            "overlapTokens ($overlapTokens) must be ≤ chunkSizeTokens / 2 (${chunkSizeTokens / 2})."
        }
    }

    fun chunk(
        text: String,
        documentId: String,
        documentName: String,
        pageOffsets: List<PageOffset> = emptyList(),
    ): List<TextChunk> {
        if (text.isBlank()) return emptyList()

        val chunkSizeChars = chunkSizeTokens * CHARS_PER_TOKEN
        val overlapChars = overlapTokens * CHARS_PER_TOKEN
        val stepChars = chunkSizeChars - overlapChars
        val maxChunkChars = maxChunkSizeTokens * CHARS_PER_TOKEN

        val chunks = mutableListOf<TextChunk>()
        var start = 0
        var chunkIndex = 0

        while (start < text.length) {
            val rawEnd = (start + chunkSizeChars).coerceAtMost(start + maxChunkChars)
            val end = rawEnd.coerceAtMost(text.length)
            val content = text.substring(start, end)

            val pageNumber = pageOffsets
                .firstOrNull { start >= it.startCharOffset && start < it.endCharOffset }
                ?.pageNumber

            chunks += TextChunk(
                id = "${documentId}_chunk_$chunkIndex",
                documentId = documentId,
                documentName = documentName,
                chunkIndex = chunkIndex,
                pageNumber = pageNumber,
                startCharOffset = start,
                endCharOffset = end,
                content = content,
            )

            chunkIndex++
            if (end >= text.length) break
            start += stepChars.coerceAtLeast(1)
        }
        return chunks
    }

    companion object {
        const val CHARS_PER_TOKEN = 4
    }
}

// ── Interfaces ───────────────────────────────────────────────────────────────

/** Contract for the on-device text embedding model. */
interface OnDeviceEmbeddingModel {
    val embeddingDimension: Int
    suspend fun initialize(modelPath: String, expectedChecksum: String): ModelLoadEvent
    suspend fun generateEmbedding(text: String): FloatArray
    fun releaseMemory()
}

/** Contract for the on-device Gemma / GGUF text generation engine. */
interface OnDeviceInferenceEngine {
    suspend fun loadModel(modelPath: String, expectedChecksum: String): ModelLoadEvent
    fun generateStream(prompt: String): Flow<OnDeviceStreamEvent>
    fun cancelGeneration()
    suspend fun benchmarkMode(): BenchmarkResult
    fun activeAccelerator(): HardwareAccelerator
    fun releaseMemory()
}

/** Vector index search result. */
data class ChunkSearchResult(
    val id: String,
    val documentId: String,
    val content: String,
    val cosineSimilarity: Float,
)

/** Contract for the local vector index. */
interface LocalVectorIndex {
    suspend fun addChunk(userId: String, chunk: TextChunk, embedding: FloatArray)
    suspend fun search(
        userId: String,
        queryEmbedding: FloatArray,
        k: Int,
        minSimilarity: Float = 0.40f,
    ): List<ChunkSearchResult>
    suspend fun deleteByDocument(userId: String, documentId: String)
}

// ── Query Routing Types ──────────────────────────────────────────────────────

/** Bitmask flags for query routing signals. */
object CapabilityBit {
    const val GEMMA_READY: Int = 0b0001
    const val EMBEDDING_READY: Int = 0b0010
    const val CHUNKS_EXIST: Int = 0b0100
    const val NETWORK_REACHABLE: Int = 0b1000
    const val ALL_ON_DEVICE_CAPABLE: Int = GEMMA_READY or EMBEDDING_READY or CHUNKS_EXIST
    const val FULLY_CAPABLE: Int = ALL_ON_DEVICE_CAPABLE or NETWORK_REACHABLE
}

/** Which inference path the router selected. */
enum class InferencePath { ON_DEVICE, CLOUD }

/** The user's explicit routing preference. */
enum class PathPreference { PREFER_ON_DEVICE, PREFER_CLOUD }

/** The result of one routing evaluation. */
data class RoutingDecision(
    val path: InferencePath,
    val capabilityBitmask: Int,
    val reason: String,
    val fallbackOccurred: Boolean = false,
)

/** Contract for the query router. */
interface QueryRouter {
    fun evaluate(capabilityBitmask: Int, userPreference: PathPreference?): RoutingDecision
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : OnDeviceDocument.kt
 * Purpose    : Domain entities for the on-device RAG pipeline — documents,
 *              models, benchmark results, routing decisions, and the two
 *              sealed event hierarchies used by use cases to stream progress.
 *
 * Architecture Layer : Domain — pure Kotlin, zero Android / third-party deps.
 *                      Mapped to/from core-database entities by the data module.
 *                      Consumed by all on-device RAG use cases and feature screens.
 *
 * Dependencies       : None (pure Kotlin data classes and sealed classes)
 *
 * Design Decision    : All On-Device RAG domain types live in one file to keep
 *                      the number of small files manageable.  If any type grows
 *                      significantly it can be extracted to its own file without
 *                      breaking callers (same package).
 *
 * Requirements: 32.6, 33.1, 33.7, 34.1, 35.1, 36.1, 37.1
 * ============================================================
 */
package com.aiassistant.domain.model

// ── OnDeviceDocument ─────────────────────────────────────────────────────────

/**
 * Pipeline state of a document being ingested into the local vector index.
 * Mirrors the string values stored in [com.aiassistant.core.database.entity.OnDeviceDocumentEntity].
 */
enum class OnDeviceIngestionStatus(val value: String) {
    PENDING("pending"),
    PROCESSING("processing"),
    READY("ready"),
    FAILED("failed");

    companion object {
        fun fromValue(v: String): OnDeviceIngestionStatus =
            entries.firstOrNull { it.value == v } ?: PENDING
    }
}

/**
 * Domain representation of a user document that has been submitted to the
 * on-device RAG ingestion pipeline.
 *
 * @param id              UUID primary key.
 * @param userId          Owner of the document.
 * @param fileName        Original file name shown in the UI.
 * @param mimeType        MIME type: "application/pdf" | "text/plain" | "text/markdown".
 * @param sizeBytes       File size in bytes (≤ 50 MB enforced by use case).
 * @param totalChunks     Number of indexed chunks; 0 while status ≠ READY.
 * @param ingestionStatus Current pipeline state.
 * @param failureStage    "extraction" | "chunking" | "embedding" when FAILED; null otherwise.
 * @param createdAt       Epoch millis of submission.
 */
data class OnDeviceDocument(
    val id: String,
    val userId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val totalChunks: Int = 0,
    val ingestionStatus: OnDeviceIngestionStatus = OnDeviceIngestionStatus.PENDING,
    val failureStage: String? = null,
    val createdAt: Long,
)

// ── OnDeviceModelInfo ─────────────────────────────────────────────────────────

/**
 * Metadata for a downloaded on-device model (Gemma or embedding model).
 *
 * @param name        Display name, e.g. "Gemma 2B INT4".
 * @param version     Version string, e.g. "2.0.0".
 * @param sizeBytes   File size on disk.
 * @param lastUsed    Epoch millis of last inference call; null if never used.
 * @param checksum    SHA-256 hex string of the model file (verified on each load).
 */
data class OnDeviceModelInfo(
    val name: String,
    val version: String,
    val sizeBytes: Long,
    val lastUsed: Long?,
    val checksum: String,
)

// ── BenchmarkResult (domain mirror of core-ai BenchmarkResult) ───────────────

/**
 * Accelerator hardware used during on-device inference.
 * Mirrors [com.aiassistant.core.ai.ondevicerag.HardwareAccelerator] for use in
 * domain entities without creating a core-ai dependency in the domain module.
 */
enum class OnDeviceAccelerator { CPU, GPU, NPU }

/**
 * Result of a benchmarkMode() run — displayed in BenchmarkScreen.
 *
 * @param accelerator       Accelerator used during the run.
 * @param ttftMeanMs        Mean time-to-first-token (ms).
 * @param ttftP95Ms         95th-percentile TTFT (ms).
 * @param tokensPerSecMean  Mean generation throughput (tokens/sec).
 * @param tokensPerSecP95   95th-percentile throughput.
 * @param peakRamMb         Peak RAM consumed (MB).
 */
data class OnDeviceBenchmarkResult(
    val accelerator: OnDeviceAccelerator,
    val ttftMeanMs: Long,
    val ttftP95Ms: Long,
    val tokensPerSecMean: Float,
    val tokensPerSecP95: Float,
    val peakRamMb: Int,
)

// ── RoutingDecision & PathPreference (domain mirror) ─────────────────────────

/**
 * Which inference path was selected by QueryRouter.
 * Mirrors [com.aiassistant.core.ai.ondevicerag.InferencePath].
 */
enum class OnDeviceInferencePath { ON_DEVICE, CLOUD }

/**
 * User's explicit routing preference stored in DataStore.
 * Mirrors [com.aiassistant.core.ai.ondevicerag.PathPreference].
 */
enum class OnDevicePathPreference { PREFER_ON_DEVICE, PREFER_CLOUD }

/**
 * The result of a single QueryRouter evaluation — returned by RouteQueryUseCase.
 *
 * @param path               Selected inference path.
 * @param capabilityBitmask  4-bit integer snapshot of capability signals.
 * @param reason             Human-readable explanation for BenchmarkScreen.
 * @param fallbackOccurred   True when the router switched path at runtime.
 */
data class OnDeviceRoutingDecision(
    val path: OnDeviceInferencePath,
    val capabilityBitmask: Int,
    val reason: String,
    val fallbackOccurred: Boolean = false,
)

// ── IngestionProgress (sealed event hierarchy) ────────────────────────────────

/**
 * Events emitted by [com.aiassistant.domain.usecase.ondevicerag.OnDeviceIngestDocumentUseCase]
 * as a document moves through the ingestion pipeline.
 *
 * The full happy-path sequence:
 *   Parsing → Chunking → Embedding(n/N) → Embedding(n/N) → … → Complete
 *
 * A single Error event terminates the flow.
 */
sealed class IngestionProgress {

    /** Text extraction started (PDF parse / plain-text read). */
    data object Parsing : IngestionProgress()

    /** Text splitting into overlapping chunks started. */
    data object Chunking : IngestionProgress()

    /**
     * Embedding in progress — emitted once per chunk.
     *
     * @param current   1-based index of the chunk being embedded.
     * @param total     Total number of chunks to embed.
     */
    data class Embedding(val current: Int, val total: Int) : IngestionProgress()

    /**
     * Ingestion completed successfully.
     *
     * @param document  The updated [OnDeviceDocument] with READY status and final chunk count.
     */
    data class Complete(val document: OnDeviceDocument) : IngestionProgress()

    /**
     * Ingestion failed at a specific pipeline stage.
     *
     * @param stage   "extraction" | "chunking" | "embedding"
     * @param message Human-readable failure description.
     */
    data class Error(val stage: String, val message: String) : IngestionProgress()
}

// ── OnDeviceQueryEvent (sealed event hierarchy) ───────────────────────────────

/**
 * Events emitted by [com.aiassistant.domain.usecase.ondevicerag.OnDeviceQueryUseCase]
 * as a RAG query executes on-device.
 *
 * The happy-path sequence:
 *   Searching → Token → Token → … → Done(citations)
 *
 * NoRelevantContent and Error are terminal events.
 */
sealed class OnDeviceQueryEvent {

    /** Vector search in progress. */
    data object Searching : OnDeviceQueryEvent()

    /** One generated text token from the Gemma inference engine. */
    data class Token(val text: String) : OnDeviceQueryEvent()

    /**
     * Query completed successfully.
     *
     * @param tokensGenerated  Number of tokens produced.
     * @param generationTimeMs Wall-clock generation time.
     * @param citations        List of source chunk references.
     */
    data class Done(
        val tokensGenerated: Int,
        val generationTimeMs: Long,
        val citations: List<ChunkCitation>,
    ) : OnDeviceQueryEvent()

    /**
     * No chunks in the local index met the minimum similarity threshold (0.40).
     * The feature screen should display "No relevant content found in local documents."
     */
    data object NoRelevantContent : OnDeviceQueryEvent()

    /**
     * Query failed at a specific stage.
     *
     * @param message Human-readable description.
     * @param stage   "embedding" | "search" | "generation" | "router"
     */
    data class Error(val message: String, val stage: String) : OnDeviceQueryEvent()
}

/**
 * A citation linking a generated response back to a source chunk.
 *
 * @param documentId      Parent document ID.
 * @param documentName    Display name of the source document.
 * @param chunkIndex      Zero-based position of the chunk within the document.
 * @param pageNumber      PDF page number (1-based), or null for TXT/Markdown.
 * @param excerpt         Short excerpt of the chunk content (≤ 200 chars).
 * @param cosineSimilarity Similarity score that caused this chunk to be retrieved.
 */
data class ChunkCitation(
    val documentId: String,
    val documentName: String,
    val chunkIndex: Int,
    val pageNumber: Int?,
    val excerpt: String,
    val cosineSimilarity: Float,
)

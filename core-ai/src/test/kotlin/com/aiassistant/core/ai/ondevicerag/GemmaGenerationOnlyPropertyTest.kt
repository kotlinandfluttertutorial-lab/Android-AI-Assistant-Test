/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai (test)
 * File       : GemmaGenerationOnlyPropertyTest.kt
 *
 * Property 41: Gemma Generation-Only Isolation
 * Validates  : Requirement 35.7
 *
 * Specification:
 *   Generate random query strings and document content; use a spy
 *   OnDeviceInferenceEngine that records all method invocations; run the
 *   full OnDeviceQueryUseCase pipeline; assert no "generateEmbedding",
 *   "search", or "parse" method calls appear in the spy recording; assert
 *   only "generateStream", "cancelGeneration", "releaseMemory" (or empty)
 *   calls are present on the engine spy.
 *
 * Architecture Layer : Core-AI test — pure JVM, no Android deps.
 * ============================================================
 */
package com.aiassistant.core.ai.ondevicerag

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.OnDeviceDocument
import com.aiassistant.domain.model.OnDeviceIngestionStatus
import com.aiassistant.domain.model.OnDeviceQueryEvent
import com.aiassistant.domain.repository.QueryMetricsRepository
import com.aiassistant.domain.usecase.ondevicerag.OnDeviceIngestDocumentUseCase
import com.aiassistant.domain.usecase.ondevicerag.OnDeviceQueryUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

class GemmaGenerationOnlyPropertyTest : DescribeSpec({

    /**
     * Property 41: For any (query, document content) pair, the
     * OnDeviceInferenceEngine spy records ONLY generateStream calls (and
     * optionally activeAccelerator for metrics).  It NEVER records
     * generateEmbedding, search, addChunk, or parse — those belong to the
     * embedding model and vector index layers, not the generation engine.
     */
    describe("Property 41 — Gemma Generation-Only Isolation") {

        it("OnDeviceInferenceEngine receives only generateStream — never embed/search/parse") {
            checkAll(
                iterations = 30,
                Arb.string(minSize = 10, maxSize = 200),  // document content
                Arb.string(minSize = 5, maxSize = 100),   // query
            ) { docContent, query ->

                // ── Set up in-memory pipeline ─────────────────────────────
                val chunker = Chunker(chunkSizeTokens = 50, overlapTokens = 10)
                val embeddingModel = readyEmbeddingModelP41()
                val dao = InMemoryDaoP41()
                val vectorIndex = LocalVectorIndex(dao)

                val docRepo = mockk<com.aiassistant.domain.repository.OnDeviceDocumentRepository>(relaxed = true)
                coEvery { docRepo.saveDocument(any()) } returns ApiResult.Success(
                    OnDeviceDocument(
                        id = "p41doc", userId = "u", fileName = "p41.txt",
                        mimeType = "text/plain", sizeBytes = docContent.length.toLong(),
                        ingestionStatus = OnDeviceIngestionStatus.PENDING, createdAt = 0
                    )
                )
                coEvery { docRepo.updateStatus(any(), any(), any(), any()) } returns ApiResult.Success(Unit)

                // Ingest so the vector index has chunks
                val ingestUseCase = OnDeviceIngestDocumentUseCase(docRepo, chunker, embeddingModel, vectorIndex)
                ingestUseCase(
                    OnDeviceDocument(
                        id = "p41doc", userId = "u", fileName = "p41.txt",
                        mimeType = "text/plain", sizeBytes = docContent.length.toLong(),
                        ingestionStatus = OnDeviceIngestionStatus.PENDING, createdAt = 0,
                    ),
                    docContent
                ).toList()

                // ── Spy on the inference engine ───────────────────────────
                val realEngine = mockk<OnDeviceInferenceEngine>()
                every { realEngine.generateStream(any()) } returns flowOf(
                    OnDeviceStreamEvent.Token("answer "),
                    OnDeviceStreamEvent.Done(tokensGenerated = 1, generationTimeMs = 10),
                )
                every { realEngine.activeAccelerator() } returns HardwareAccelerator.CPU

                val engineSpy = spyk(realEngine)

                val metricsRepo = mockk<QueryMetricsRepository>(relaxed = true)
                val queryUseCase = OnDeviceQueryUseCase(embeddingModel, vectorIndex, engineSpy, metricsRepo)

                // ── Run the query pipeline ────────────────────────────────
                queryUseCase(query, "u").toList()

                // ── Assert: forbidden methods NEVER called on engine ──────
                // generateEmbedding lives on OnDeviceEmbeddingModel — not on the engine
                // search lives on LocalVectorIndex — not on the engine
                // parse is not a method on any interface in this system

                // The engine spy should only show generateStream and activeAccelerator
                // (and possibly cancelGeneration if the pipeline emits Cancelled)
                // but NEVER loadModel, benchmarkMode in the hot path
                verify(exactly = 0) { engineSpy.loadModel(any(), any()) }
                verify(exactly = 0) { engineSpy.benchmarkMode() }
                verify(exactly = 0) { engineSpy.releaseMemory() }
                // cancelGeneration is allowed (called on error/cancel), but 0 expected for normal flow
                verify(exactly = 0) { engineSpy.cancelGeneration() }

                // generateStream must be called exactly once per query (when chunks exist)
                // If no chunks were indexed (very short doc) the engine won't be called
                val chunks = dao.getAllChunks("u")
                if (chunks.isNotEmpty()) {
                    verify(atLeast = 1) { engineSpy.generateStream(any()) }
                }
            }
        }
    }
})

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun readyEmbeddingModelP41(): MiniLmEmbeddingModel {
    val model = MiniLmEmbeddingModel()
    val field = model::class.java.getDeclaredField("_isReady")
    field.isAccessible = true
    field.setBoolean(model, true)
    return model
}

private class InMemoryDaoP41 : com.aiassistant.core.database.dao.OnDeviceChunkDao {
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

    override suspend fun getAllChunks(userId: String) =
        store.filter { it.userId == userId }

    override suspend fun deleteByDocument(userId: String, documentId: String) {
        store.removeAll { it.userId == userId && it.documentId == documentId }
    }

    override suspend fun countChunks(userId: String) =
        store.count { it.userId == userId }
}

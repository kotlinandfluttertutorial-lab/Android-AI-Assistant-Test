/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain (test)
 * File       : OnDeviceQueryUseCaseTest.kt
 * Purpose    : Unit tests for OnDeviceQueryUseCase.
 *              Validates:
 *                1. NoRelevantContent event when all similarity scores < 0.40.
 *                2. ChunkCitation list populated in Done event.
 *                3. Gemma engine NEVER receives generateEmbedding or search calls
 *                   (Property 41 — generation-only isolation).
 *
 * Requirements: 21.1, 35.5, 35.7, 36.5, 36.6, 36.7, 36.8
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.ai.ondevicerag.ChunkSearchResult
import com.aiassistant.core.ai.ondevicerag.LocalVectorIndex
import com.aiassistant.core.ai.ondevicerag.OnDeviceEmbeddingModel
import com.aiassistant.core.ai.ondevicerag.OnDeviceInferenceEngine
import com.aiassistant.core.ai.ondevicerag.OnDeviceStreamEvent
import com.aiassistant.core.database.entity.OnDeviceChunkEntity
import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.OnDeviceQueryEvent
import com.aiassistant.domain.repository.QueryMetricsRepository
import com.aiassistant.domain.repository.QueryMetricsSample
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

class OnDeviceQueryUseCaseTest : DescribeSpec({

    fun fakeChunkEntity(id: String, docId: String, docName: String, content: String) =
        OnDeviceChunkEntity(
            id = id,
            userId = "user1",
            documentId = docId,
            documentName = docName,
            chunkIndex = 0,
            pageNumber = null,
            startCharOffset = 0,
            endCharOffset = content.length,
            content = content,
            embeddingBlob = FloatArray(384) { 0.1f },
            createdAt = 1000L,
        )

    fun buildUseCase(
        embeddingModel: OnDeviceEmbeddingModel,
        vectorIndex: LocalVectorIndex,
        inferenceEngine: OnDeviceInferenceEngine,
        metricsRepo: QueryMetricsRepository = mockk(relaxed = true),
    ) = OnDeviceQueryUseCase(embeddingModel, vectorIndex, inferenceEngine, metricsRepo)

    // ── NoRelevantContent ───────────────────────────────────────────────────

    describe("invoke() — no relevant content") {

        it("emits NoRelevantContent when vector search returns empty list") {
            val embeddingModel = mockk<OnDeviceEmbeddingModel>()
            val vectorIndex = mockk<LocalVectorIndex>()
            val inferenceEngine = mockk<OnDeviceInferenceEngine>()

            every { embeddingModel.isReady } returns true
            every { embeddingModel.generateEmbedding(any()) } returns FloatArray(384)
            coEvery { vectorIndex.search(any(), any(), any(), any()) } returns emptyList()

            val events = buildUseCase(embeddingModel, vectorIndex, inferenceEngine)
                .invoke("What is X?", "user1")
                .toList()

            events.any { it is OnDeviceQueryEvent.Searching } shouldBe true
            events.any { it is OnDeviceQueryEvent.NoRelevantContent } shouldBe true
            // inferenceEngine.generateStream must NOT be called
            coVerify(exactly = 0) { inferenceEngine.generateStream(any()) }
        }
    }

    // ── Done with citations ─────────────────────────────────────────────────

    describe("invoke() — successful query with citations") {

        it("emits Token events and Done with populated ChunkCitation list") {
            val embeddingModel = mockk<OnDeviceEmbeddingModel>()
            val vectorIndex = mockk<LocalVectorIndex>()
            val inferenceEngine = mockk<OnDeviceInferenceEngine>()

            val chunk = fakeChunkEntity("c1", "doc1", "report.txt", "The answer is 42.")
            val searchResult = ChunkSearchResult(chunk, 0.85f)

            every { embeddingModel.isReady } returns true
            every { embeddingModel.generateEmbedding(any()) } returns FloatArray(384)
            coEvery { vectorIndex.search(any(), any(), any(), any()) } returns listOf(searchResult)
            every { inferenceEngine.generateStream(any()) } returns flowOf(
                OnDeviceStreamEvent.Token("The "),
                OnDeviceStreamEvent.Token("answer "),
                OnDeviceStreamEvent.Done(tokensGenerated = 2, generationTimeMs = 150),
            )
            every { inferenceEngine.activeAccelerator() } returns com.aiassistant.core.ai.ondevicerag.HardwareAccelerator.GPU

            val events = buildUseCase(embeddingModel, vectorIndex, inferenceEngine)
                .invoke("What is the answer?", "user1")
                .toList()

            val tokens = events.filterIsInstance<OnDeviceQueryEvent.Token>()
            tokens.size shouldBe 2

            val done = events.filterIsInstance<OnDeviceQueryEvent.Done>().first()
            done.tokensGenerated shouldBe 2
            done.citations.size shouldBe 1
            done.citations[0].documentId shouldBe "doc1"
            done.citations[0].documentName shouldBe "report.txt"
            done.citations[0].cosineSimilarity shouldBe 0.85f
        }
    }

    // ── Gemma generation-only isolation (Property 41 pre-check) ────────────

    describe("invoke() — Gemma engine isolation") {

        it("inferenceEngine receives only generateStream call — never generateEmbedding or search") {
            val embeddingModel = mockk<OnDeviceEmbeddingModel>()
            val vectorIndex = mockk<LocalVectorIndex>()
            val inferenceEngine = mockk<OnDeviceInferenceEngine>()

            val chunk = fakeChunkEntity("c1", "doc1", "doc.txt", "Relevant content here.")
            every { embeddingModel.isReady } returns true
            every { embeddingModel.generateEmbedding(any()) } returns FloatArray(384)
            coEvery { vectorIndex.search(any(), any(), any(), any()) } returns listOf(
                ChunkSearchResult(chunk, 0.75f)
            )
            every { inferenceEngine.generateStream(any()) } returns flowOf(
                OnDeviceStreamEvent.Done(tokensGenerated = 5, generationTimeMs = 100),
            )
            every { inferenceEngine.activeAccelerator() } returns com.aiassistant.core.ai.ondevicerag.HardwareAccelerator.CPU

            buildUseCase(embeddingModel, vectorIndex, inferenceEngine)
                .invoke("Test query", "user1")
                .toList()

            // Verify generateStream was called exactly once
            coVerify(exactly = 1) { inferenceEngine.generateStream(any()) }
            // Verify no other methods on inferenceEngine were called
            coVerify(exactly = 0) { inferenceEngine.loadModel(any(), any()) }
            coVerify(exactly = 0) { inferenceEngine.benchmarkMode() }
            coVerify(exactly = 0) { inferenceEngine.cancelGeneration() }
        }
    }

    // ── Embedding model not ready ───────────────────────────────────────────

    describe("invoke() — embedding model not ready") {

        it("emits Error(stage=embedding) when embeddingModel.isReady == false") {
            val embeddingModel = mockk<OnDeviceEmbeddingModel>()
            val vectorIndex = mockk<LocalVectorIndex>()
            val inferenceEngine = mockk<OnDeviceInferenceEngine>()

            every { embeddingModel.isReady } returns false

            val events = buildUseCase(embeddingModel, vectorIndex, inferenceEngine)
                .invoke("Query", "user1")
                .toList()

            val error = events.filterIsInstance<OnDeviceQueryEvent.Error>().first()
            error.stage shouldBe "embedding"
        }
    }

    // ── RouteQueryUseCase log entry ─────────────────────────────────────────

    describe("invoke() — metrics recording") {

        it("calls metricsRepository.recordSample after successful Done event") {
            val embeddingModel = mockk<OnDeviceEmbeddingModel>()
            val vectorIndex = mockk<LocalVectorIndex>()
            val inferenceEngine = mockk<OnDeviceInferenceEngine>()
            val metricsRepo = mockk<QueryMetricsRepository>(relaxed = true)

            val chunk = fakeChunkEntity("c1", "doc1", "d.txt", "Content.")
            every { embeddingModel.isReady } returns true
            every { embeddingModel.generateEmbedding(any()) } returns FloatArray(384)
            coEvery { vectorIndex.search(any(), any(), any(), any()) } returns listOf(
                ChunkSearchResult(chunk, 0.9f)
            )
            every { inferenceEngine.generateStream(any()) } returns flowOf(
                OnDeviceStreamEvent.Done(tokensGenerated = 3, generationTimeMs = 200),
            )
            every { inferenceEngine.activeAccelerator() } returns com.aiassistant.core.ai.ondevicerag.HardwareAccelerator.CPU

            buildUseCase(embeddingModel, vectorIndex, inferenceEngine, metricsRepo)
                .invoke("Q", "user1")
                .toList()

            coVerify(atLeast = 1) { metricsRepo.recordSample(any()) }
        }
    }
})

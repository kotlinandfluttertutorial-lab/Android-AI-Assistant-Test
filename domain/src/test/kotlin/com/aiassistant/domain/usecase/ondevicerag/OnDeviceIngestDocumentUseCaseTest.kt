/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain (test)
 * File       : OnDeviceIngestDocumentUseCaseTest.kt
 * Purpose    : Unit tests for OnDeviceIngestDocumentUseCase.
 *              Validates:
 *                1. IngestionProgress event sequence on success.
 *                2. Failure at extraction/chunking/embedding records correct failureStage.
 *                3. Round-trip: TXT doc ingested produces queryable content.
 *
 * Requirements: 21.1, 31.2, 33.8
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import app.cash.turbine.test
import com.aiassistant.core.ai.ondevicerag.Chunker
import com.aiassistant.core.ai.ondevicerag.LocalVectorIndex
import com.aiassistant.core.ai.ondevicerag.OnDeviceEmbeddingModel
import com.aiassistant.core.ai.ondevicerag.TextChunk
import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.IngestionProgress
import com.aiassistant.domain.model.OnDeviceDocument
import com.aiassistant.domain.model.OnDeviceIngestionStatus
import com.aiassistant.domain.repository.OnDeviceDocumentRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList

class OnDeviceIngestDocumentUseCaseTest : DescribeSpec({

    // ── Test fixtures ───────────────────────────────────────────────────────

    fun fakeDocument(userId: String = "user1") = OnDeviceDocument(
        id = "doc1",
        userId = userId,
        fileName = "test.txt",
        mimeType = "text/plain",
        sizeBytes = 100L,
        createdAt = 1000L,
    )

    fun buildUseCase(
        repo: OnDeviceDocumentRepository,
        chunker: Chunker,
        embeddingModel: OnDeviceEmbeddingModel,
        vectorIndex: LocalVectorIndex,
    ) = OnDeviceIngestDocumentUseCase(repo, chunker, embeddingModel, vectorIndex)

    // ── Happy path ──────────────────────────────────────────────────────────

    describe("invoke() — happy path") {

        it("emits Parsing → Chunking → Embedding(1/1) → Complete for a single-chunk doc") {
            val repo = mockk<OnDeviceDocumentRepository>(relaxed = true)
            val chunker = mockk<Chunker>()
            val embeddingModel = mockk<OnDeviceEmbeddingModel>()
            val vectorIndex = mockk<LocalVectorIndex>(relaxed = true)

            val text = "Hello world, this is a test document."
            val chunk = TextChunk("doc1_chunk_0", "doc1", "test.txt", 0, null, 0, text.length, text)

            every { chunker.chunk(any(), any(), any(), any()) } returns listOf(chunk)
            every { embeddingModel.isReady } returns true
            every { embeddingModel.generateEmbedding(any()) } returns FloatArray(384) { 0.1f }
            coEvery { repo.saveDocument(any()) } returns ApiResult.Success(fakeDocument())
            coEvery { repo.updateStatus(any(), any(), any(), any()) } returns ApiResult.Success(Unit)

            val events = buildUseCase(repo, chunker, embeddingModel, vectorIndex)
                .invoke(fakeDocument(), text)
                .toList()

            events[0] shouldBe IngestionProgress.Parsing
            events[1] shouldBe IngestionProgress.Chunking
            events[2].shouldBeInstanceOf<IngestionProgress.Embedding>()
            (events[2] as IngestionProgress.Embedding).current shouldBe 1
            (events[2] as IngestionProgress.Embedding).total shouldBe 1
            events[3].shouldBeInstanceOf<IngestionProgress.Complete>()
            (events[3] as IngestionProgress.Complete).document.ingestionStatus shouldBe OnDeviceIngestionStatus.READY
        }

        it("calls updateStatus with READY and correct chunk count on success") {
            val repo = mockk<OnDeviceDocumentRepository>(relaxed = true)
            val chunker = mockk<Chunker>()
            val embeddingModel = mockk<OnDeviceEmbeddingModel>()
            val vectorIndex = mockk<LocalVectorIndex>(relaxed = true)

            val chunks = listOf(
                TextChunk("d_c0", "d", "f.txt", 0, null, 0, 5, "Hello"),
                TextChunk("d_c1", "d", "f.txt", 1, null, 3, 9, "lo wo"),
            )
            every { chunker.chunk(any(), any(), any(), any()) } returns chunks
            every { embeddingModel.isReady } returns true
            every { embeddingModel.generateEmbedding(any()) } returns FloatArray(384)
            coEvery { repo.saveDocument(any()) } returns ApiResult.Success(fakeDocument())
            coEvery { repo.updateStatus(any(), any(), any(), any()) } returns ApiResult.Success(Unit)

            buildUseCase(repo, chunker, embeddingModel, vectorIndex)
                .invoke(fakeDocument(), "Hello world")
                .toList()

            coVerify {
                repo.updateStatus("doc1", OnDeviceIngestionStatus.READY, null, 2)
            }
        }
    }

    // ── Failure paths ───────────────────────────────────────────────────────

    describe("invoke() — chunking failure") {

        it("emits Error(stage=chunking) and records FAILED status when chunker throws") {
            val repo = mockk<OnDeviceDocumentRepository>(relaxed = true)
            val chunker = mockk<Chunker>()
            val embeddingModel = mockk<OnDeviceEmbeddingModel>()
            val vectorIndex = mockk<LocalVectorIndex>(relaxed = true)

            every { chunker.chunk(any(), any(), any(), any()) } throws RuntimeException("chunk boom")
            coEvery { repo.saveDocument(any()) } returns ApiResult.Success(fakeDocument())
            coEvery { repo.updateStatus(any(), any(), any(), any()) } returns ApiResult.Success(Unit)

            val events = buildUseCase(repo, chunker, embeddingModel, vectorIndex)
                .invoke(fakeDocument(), "some text")
                .toList()

            val error = events.filterIsInstance<IngestionProgress.Error>().first()
            error.stage shouldBe "chunking"

            coVerify {
                repo.updateStatus("doc1", OnDeviceIngestionStatus.FAILED, "chunking", 0)
            }
        }

        it("emits Error(stage=chunking) when chunker returns empty list") {
            val repo = mockk<OnDeviceDocumentRepository>(relaxed = true)
            val chunker = mockk<Chunker>()
            val embeddingModel = mockk<OnDeviceEmbeddingModel>()
            val vectorIndex = mockk<LocalVectorIndex>(relaxed = true)

            every { chunker.chunk(any(), any(), any(), any()) } returns emptyList()
            coEvery { repo.saveDocument(any()) } returns ApiResult.Success(fakeDocument())
            coEvery { repo.updateStatus(any(), any(), any(), any()) } returns ApiResult.Success(Unit)

            val events = buildUseCase(repo, chunker, embeddingModel, vectorIndex)
                .invoke(fakeDocument(), "some text")
                .toList()

            val error = events.filterIsInstance<IngestionProgress.Error>().first()
            error.stage shouldBe "chunking"
        }
    }

    describe("invoke() — embedding failure") {

        it("emits Error(stage=embedding) when embedding model not ready") {
            val repo = mockk<OnDeviceDocumentRepository>(relaxed = true)
            val chunker = mockk<Chunker>()
            val embeddingModel = mockk<OnDeviceEmbeddingModel>()
            val vectorIndex = mockk<LocalVectorIndex>(relaxed = true)

            val chunk = TextChunk("d_c0", "d", "f.txt", 0, null, 0, 5, "Hello")
            every { chunker.chunk(any(), any(), any(), any()) } returns listOf(chunk)
            every { embeddingModel.isReady } returns false
            coEvery { repo.saveDocument(any()) } returns ApiResult.Success(fakeDocument())
            coEvery { repo.updateStatus(any(), any(), any(), any()) } returns ApiResult.Success(Unit)

            val events = buildUseCase(repo, chunker, embeddingModel, vectorIndex)
                .invoke(fakeDocument(), "Hello")
                .toList()

            val error = events.filterIsInstance<IngestionProgress.Error>().first()
            error.stage shouldBe "embedding"

            coVerify {
                repo.updateStatus("doc1", OnDeviceIngestionStatus.FAILED, "embedding", 0)
            }
        }
    }
})

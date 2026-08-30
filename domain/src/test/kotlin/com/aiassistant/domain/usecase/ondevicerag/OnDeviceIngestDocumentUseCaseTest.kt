/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain (test)
 * File       : OnDeviceIngestDocumentUseCaseTest.kt
 * Purpose    : Unit tests for OnDeviceIngestDocumentUseCase.
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.Chunker
import com.aiassistant.core.common.LocalVectorIndex
import com.aiassistant.core.common.OnDeviceEmbeddingModel
import com.aiassistant.core.common.TextChunk
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
import kotlinx.coroutines.flow.toList

class OnDeviceIngestDocumentUseCaseTest :
    DescribeSpec({

        fun fakeDocument() = OnDeviceDocument(
            id = "doc1",
            userId = "user1",
            fileName = "test.txt",
            mimeType = "text/plain",
            sizeBytes = 100L,
            createdAt = 0
        )

        describe("invoke() — happy path") {
            it("emits expected progress events") {
                val repo = mockk<OnDeviceDocumentRepository>(relaxed = true)
                val chunker = mockk<Chunker>()
                val embeddingModel = mockk<OnDeviceEmbeddingModel>()
                val vectorIndex = mockk<LocalVectorIndex>(relaxed = true)

                val text = "Hello world"
                val chunk = TextChunk("d1_c0", "doc1", "test.txt", 0, null, 0, 11, text)

                every { chunker.chunk(any(), any(), any(), any()) } returns listOf(chunk)
                coEvery { embeddingModel.generateEmbedding(any()) } returns FloatArray(384)
                coEvery { repo.saveDocument(any()) } returns ApiResult.Success(fakeDocument())

                val useCase = OnDeviceIngestDocumentUseCase(repo, chunker, embeddingModel, vectorIndex)
                val events = useCase(fakeDocument(), text).toList()

                events[0] shouldBe IngestionProgress.Parsing
                events[1] shouldBe IngestionProgress.Chunking
                events[2].shouldBeInstanceOf<IngestionProgress.Embedding>()
                events[3].shouldBeInstanceOf<IngestionProgress.Complete>()
            }
        }

        describe("invoke() — failures") {
            it("handles chunking failure") {
                val repo = mockk<OnDeviceDocumentRepository>(relaxed = true)
                val chunker = mockk<Chunker>()
                val embeddingModel = mockk<OnDeviceEmbeddingModel>()
                val vectorIndex = mockk<LocalVectorIndex>(relaxed = true)

                every { chunker.chunk(any(), any(), any(), any()) } throws RuntimeException("boom")
                coEvery { repo.saveDocument(any()) } returns ApiResult.Success(fakeDocument())

                val useCase = OnDeviceIngestDocumentUseCase(repo, chunker, embeddingModel, vectorIndex)
                val events = useCase(fakeDocument(), "text").toList()

                val error = events.filterIsInstance<IngestionProgress.Error>().first()
                error.stage shouldBe "chunking"
                coVerify { repo.updateStatus("doc1", OnDeviceIngestionStatus.FAILED, "chunking", 0) }
            }
        }
    })

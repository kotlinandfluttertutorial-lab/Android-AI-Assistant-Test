/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain (test)
 * File       : OnDeviceQueryUseCaseTest.kt
 * Purpose    : Unit tests for OnDeviceQueryUseCase.
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.common.ChunkSearchResult
import com.aiassistant.core.common.HardwareAccelerator
import com.aiassistant.core.common.LocalVectorIndex
import com.aiassistant.core.common.OnDeviceEmbeddingModel
import com.aiassistant.core.common.OnDeviceInferenceEngine
import com.aiassistant.core.common.OnDeviceStreamEvent
import com.aiassistant.domain.model.OnDeviceQueryEvent
import com.aiassistant.domain.repository.QueryMetricsRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

class OnDeviceQueryUseCaseTest :
    DescribeSpec({

        fun buildUseCase(
            embeddingModel: OnDeviceEmbeddingModel,
            vectorIndex: LocalVectorIndex,
            inferenceEngine: OnDeviceInferenceEngine,
            metricsRepo: QueryMetricsRepository = mockk(relaxed = true)
        ) = OnDeviceQueryUseCase(embeddingModel, vectorIndex, inferenceEngine, metricsRepo)

        describe("invoke() — no relevant content") {
            it("emits NoRelevantContent when vector search returns empty list") {
                val embeddingModel = mockk<OnDeviceEmbeddingModel>()
                val vectorIndex = mockk<LocalVectorIndex>()
                val inferenceEngine = mockk<OnDeviceInferenceEngine>()

                coEvery { embeddingModel.generateEmbedding(any()) } returns FloatArray(384)
                coEvery { vectorIndex.search(any(), any(), any(), any()) } returns emptyList()

                val events = buildUseCase(embeddingModel, vectorIndex, inferenceEngine)
                    .invoke("What is X?", "user1")
                    .toList()

                events.any { it is OnDeviceQueryEvent.NoRelevantContent } shouldBe true
                coVerify(exactly = 0) { inferenceEngine.generateStream(any()) }
            }
        }

        describe("invoke() — successful query with citations") {
            it("emits Token events and Done with populated ChunkCitation list") {
                val embeddingModel = mockk<OnDeviceEmbeddingModel>()
                val vectorIndex = mockk<LocalVectorIndex>()
                val inferenceEngine = mockk<OnDeviceInferenceEngine>()

                val searchResult = ChunkSearchResult("c1", "doc1", "The answer is 42.", 0.85f)

                coEvery { embeddingModel.generateEmbedding(any()) } returns FloatArray(384)
                coEvery { vectorIndex.search(any(), any(), any(), any()) } returns listOf(searchResult)
                every { inferenceEngine.generateStream(any()) } returns flowOf(
                    OnDeviceStreamEvent.Token("The "),
                    OnDeviceStreamEvent.Done(tokensGenerated = 1, generationTimeMs = 150)
                )
                every { inferenceEngine.activeAccelerator() } returns HardwareAccelerator.GPU

                val events = buildUseCase(embeddingModel, vectorIndex, inferenceEngine)
                    .invoke("What is the answer?", "user1")
                    .toList()

                events.filterIsInstance<OnDeviceQueryEvent.Token>().size shouldBe 1
                val done = events.filterIsInstance<OnDeviceQueryEvent.Done>().first()
                done.citations.size shouldBe 1
                done.citations[0].documentId shouldBe "doc1"
            }
        }
    })

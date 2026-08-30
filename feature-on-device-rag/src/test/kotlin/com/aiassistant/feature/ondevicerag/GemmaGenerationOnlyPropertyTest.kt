/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag (test)
 * File       : GemmaGenerationOnlyPropertyTest.kt
 * Purpose    : Property tests for Gemma generation-only isolation.
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import com.aiassistant.core.ai.ondevicerag.LocalVectorIndexImpl
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.Chunker
import com.aiassistant.core.common.HardwareAccelerator
import com.aiassistant.core.common.OnDeviceEmbeddingModel
import com.aiassistant.core.common.OnDeviceInferenceEngine
import com.aiassistant.core.common.OnDeviceStreamEvent
import com.aiassistant.domain.model.OnDeviceDocument
import com.aiassistant.domain.model.OnDeviceIngestionStatus
import com.aiassistant.domain.repository.OnDeviceDocumentRepository
import com.aiassistant.domain.repository.QueryMetricsRepository
import com.aiassistant.domain.usecase.ondevicerag.OnDeviceIngestDocumentUseCase
import com.aiassistant.domain.usecase.ondevicerag.OnDeviceQueryUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

class GemmaGenerationOnlyPropertyTest :
    DescribeSpec({

        describe("Property 41 — Gemma Generation-Only Isolation") {

            it("OnDeviceInferenceEngine receives only generateStream") {
                checkAll(
                    iterations = 30,
                    Arb.string(minSize = 10, maxSize = 200),
                    Arb.string(minSize = 5, maxSize = 100)
                ) { docContent, query ->

                    val chunker = Chunker(chunkSizeTokens = 50, overlapTokens = 10)
                    val embeddingModel = mockk<OnDeviceEmbeddingModel>()
                    coEvery { embeddingModel.generateEmbedding(any()) } returns FloatArray(384) { 0.1f }

                    val dao = InMemoryDaoP41()
                    val vectorIndex = LocalVectorIndexImpl(dao)

                    val docRepo = mockk<OnDeviceDocumentRepository>(relaxed = true)
                    coEvery { docRepo.saveDocument(any()) } returns ApiResult.Success(
                        OnDeviceDocument(
                            id = "p41doc",
                            userId = "u",
                            fileName = "p41.txt",
                            mimeType = "text/plain",
                            sizeBytes = docContent.length.toLong(),
                            ingestionStatus = OnDeviceIngestionStatus.PENDING,
                            createdAt = 0
                        )
                    )

                    val ingestUseCase = OnDeviceIngestDocumentUseCase(docRepo, chunker, embeddingModel, vectorIndex)
                    ingestUseCase(
                        OnDeviceDocument(
                            id = "p41doc",
                            userId = "u",
                            fileName = "p41.txt",
                            mimeType = "text/plain",
                            sizeBytes = docContent.length.toLong(),
                            ingestionStatus = OnDeviceIngestionStatus.PENDING,
                            createdAt = 0
                        ),
                        docContent
                    ).toList()

                    val realEngine = mockk<OnDeviceInferenceEngine>()
                    every { realEngine.generateStream(any()) } returns flowOf(
                        OnDeviceStreamEvent.Token("answer "),
                        OnDeviceStreamEvent.Done(tokensGenerated = 1, generationTimeMs = 10)
                    )
                    every { realEngine.activeAccelerator() } returns HardwareAccelerator.CPU

                    val metricsRepo = mockk<QueryMetricsRepository>(relaxed = true)
                    val queryUseCase = OnDeviceQueryUseCase(embeddingModel, vectorIndex, realEngine, metricsRepo)

                    queryUseCase(query, "u").toList()

                    coVerify(exactly = 0) { realEngine.loadModel(any(), any()) }
                    coVerify(exactly = 0) { realEngine.benchmarkMode() }
                    verify(exactly = 0) { realEngine.releaseMemory() }
                }
            }
        }
    })

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

    override suspend fun getAllChunks(userId: String) = store.filter { it.userId == userId }

    override suspend fun deleteByDocument(userId: String, documentId: String) {
        store.removeAll { it.userId == userId && it.documentId == documentId }
    }

    override suspend fun countChunks(userId: String) = store.count { it.userId == userId }

    override suspend fun totalEmbeddingBytes(userId: String, embeddingDimension: Int): Long = 0L
}

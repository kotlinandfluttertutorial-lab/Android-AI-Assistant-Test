/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag (test)
 * File       : OnDeviceRagRoundTripPropertyTest.kt
 * Purpose    : Property tests for on-device RAG round-trip.
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
import com.aiassistant.domain.model.OnDeviceQueryEvent
import com.aiassistant.domain.repository.OnDeviceDocumentRepository
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

class OnDeviceRagRoundTripPropertyTest : DescribeSpec({

    describe("Property 37 — On-Device RAG Round-Trip") {

        it("verbatim phrase from ingested document appears in Done citations") {
            checkAll(
                iterations = 20,
                Arb.string(minSize = 100, maxSize = 2000)
            ) { rawText ->
                if (rawText.split(" ").size < 5) return@checkAll

                val documentId = "prop37_doc"
                val userId = "user_prop37"

                val document = OnDeviceDocument(
                    id = documentId,
                    userId = userId,
                    fileName = "prop37.txt",
                    mimeType = "text/plain",
                    sizeBytes = rawText.length.toLong(),
                    ingestionStatus = OnDeviceIngestionStatus.PENDING,
                    createdAt = 0,
                )

                val chunker = Chunker(chunkSizeTokens = 50, overlapTokens = 10)
                val embeddingModel = mockk<OnDeviceEmbeddingModel>()
                coEvery { embeddingModel.generateEmbedding(any()) } returns FloatArray(384) { 0.1f }

                val dao = InMemoryOnDeviceChunkDao()
                val vectorIndex = LocalVectorIndexImpl(dao)

                val docRepo = mockk<OnDeviceDocumentRepository>(relaxed = true)
                coEvery { docRepo.saveDocument(any()) } returns ApiResult.Success(document)

                val ingestUseCase = OnDeviceIngestDocumentUseCase(docRepo, chunker, embeddingModel, vectorIndex)
                ingestUseCase(document, rawText).toList()

                val words = rawText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.size < 5) return@checkAll
                val phrase = words.take(5).joinToString(" ")

                val inferenceEngine = mockk<OnDeviceInferenceEngine>()
                every { inferenceEngine.generateStream(any()) } answers {
                    flowOf(
                        OnDeviceStreamEvent.Token("answer"),
                        OnDeviceStreamEvent.Done(tokensGenerated = 10, generationTimeMs = 50),
                    )
                }
                every { inferenceEngine.activeAccelerator() } returns HardwareAccelerator.CPU

                val metricsRepo = mockk<QueryMetricsRepository>(relaxed = true)
                val queryUseCase = OnDeviceQueryUseCase(embeddingModel, vectorIndex, inferenceEngine, metricsRepo)
                val events = queryUseCase(phrase, userId).toList()

                val doneEvent = events.filterIsInstance<OnDeviceQueryEvent.Done>().firstOrNull()
                    ?: return@checkAll

                val hasCitation = doneEvent.citations.any { it.documentId == documentId }
                hasCitation shouldBe true
            }
        }
    }
})

private class InMemoryOnDeviceChunkDao : com.aiassistant.core.database.dao.OnDeviceChunkDao {
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

    override suspend fun totalEmbeddingBytes(userId: String, embeddingDimension: Int): Long = 0L
}

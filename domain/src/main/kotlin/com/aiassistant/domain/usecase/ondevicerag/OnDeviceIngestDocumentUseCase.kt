/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : OnDeviceIngestDocumentUseCase.kt
 * Purpose    : Orchestrates the full on-device document ingestion pipeline.
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.common.Chunker
import com.aiassistant.core.common.LocalVectorIndex
import com.aiassistant.core.common.OnDeviceEmbeddingModel
import com.aiassistant.domain.model.IngestionProgress
import com.aiassistant.domain.model.OnDeviceDocument
import com.aiassistant.domain.model.OnDeviceIngestionStatus
import com.aiassistant.domain.repository.OnDeviceDocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class OnDeviceIngestDocumentUseCase @Inject constructor(
    private val documentRepository: OnDeviceDocumentRepository,
    private val chunker: Chunker,
    private val embeddingModel: OnDeviceEmbeddingModel,
    private val vectorIndex: LocalVectorIndex,
) {

    operator fun invoke(document: OnDeviceDocument, rawText: String): Flow<IngestionProgress> = flow {
        documentRepository.saveDocument(document)
        documentRepository.updateStatus(document.id, OnDeviceIngestionStatus.PROCESSING, null, 0)
        emit(IngestionProgress.Parsing)

        emit(IngestionProgress.Chunking)
        val chunks = try {
            chunker.chunk(text = rawText, documentId = document.id, documentName = document.fileName)
        } catch (e: Exception) {
            handleError(document.id, "chunking", "Chunking failed: ${e.message}")
            return@flow
        }

        if (chunks.isEmpty()) {
            handleError(document.id, "chunking", "No chunks produced.")
            return@flow
        }

        chunks.forEachIndexed { idx, chunk ->
            emit(IngestionProgress.Embedding(current = idx + 1, total = chunks.size))
            try {
                val embedding = embeddingModel.generateEmbedding(chunk.content)
                vectorIndex.addChunk(document.userId, chunk, embedding)
            } catch (e: Exception) {
                handleError(document.id, "embedding", "Ingestion failed at chunk $idx: ${e.message}", idx)
                return@flow
            }
        }

        val readyDoc = document.copy(ingestionStatus = OnDeviceIngestionStatus.READY, totalChunks = chunks.size)
        documentRepository.updateStatus(document.id, OnDeviceIngestionStatus.READY, null, chunks.size)
        emit(IngestionProgress.Complete(readyDoc))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<IngestionProgress>.handleError(
        docId: String, stage: String, msg: String, chunksSoFar: Int = 0
    ) {
        documentRepository.updateStatus(docId, OnDeviceIngestionStatus.FAILED, stage, chunksSoFar)
        emit(IngestionProgress.Error(stage, msg))
    }
}

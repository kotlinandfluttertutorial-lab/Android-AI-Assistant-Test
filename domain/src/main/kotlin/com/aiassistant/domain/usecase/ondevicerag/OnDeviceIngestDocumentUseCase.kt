/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : OnDeviceIngestDocumentUseCase.kt
 * Purpose    : Orchestrates the full on-device document ingestion pipeline:
 *              parse → chunk → embed → index → persist.
 *              Emits IngestionProgress events so the feature screen can show
 *              a live progress indicator without polling.
 *
 * Architecture Layer : Domain — pure Kotlin use case.
 *                      Depends on OnDeviceDocumentRepository (persistence),
 *                      Chunker (text splitting), OnDeviceEmbeddingModel (vectors),
 *                      and LocalVectorIndex (storage + search).
 *
 * Design Decision    : The use case owns the ingestion orchestration so that
 *                      the feature ViewModel only needs to collect a Flow and
 *                      update UI state — no ingestion logic leaks upward.
 *                      failureStage is recorded at the repository layer so the
 *                      document's status row always reflects which step failed,
 *                      even if the app is killed mid-ingestion.
 *
 * Requirements: 33.1, 33.2, 33.3, 33.6, 33.7, 33.9, 33.10
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.ai.ondevicerag.Chunker
import com.aiassistant.core.ai.ondevicerag.LocalVectorIndex
import com.aiassistant.core.ai.ondevicerag.OnDeviceEmbeddingModel
import com.aiassistant.domain.model.IngestionProgress
import com.aiassistant.domain.model.OnDeviceDocument
import com.aiassistant.domain.model.OnDeviceIngestionStatus
import com.aiassistant.domain.repository.OnDeviceDocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Ingests a document into the on-device RAG vector index.
 *
 * ### Accepted MIME types
 * "application/pdf" | "text/plain" | "text/markdown" | "text/x-markdown"
 *
 * ### Maximum file size
 * 50 MB — enforced by the feature ViewModel before calling this use case.
 *
 * ### Progress events (in order)
 * [IngestionProgress.Parsing] → [IngestionProgress.Chunking] →
 * [IngestionProgress.Embedding](1/N) → … → [IngestionProgress.Embedding](N/N) →
 * [IngestionProgress.Complete]
 *
 * A single [IngestionProgress.Error] terminates the flow on any stage failure.
 *
 * @param documentRepository Persists document metadata and status transitions.
 * @param chunker            Splits extracted text into overlapping [TextChunk]s.
 * @param embeddingModel     Converts each chunk to a float32 embedding vector.
 * @param vectorIndex        Stores chunks + embeddings in the local Room index.
 */
class OnDeviceIngestDocumentUseCase @Inject constructor(
    private val documentRepository: OnDeviceDocumentRepository,
    private val chunker: Chunker,
    private val embeddingModel: OnDeviceEmbeddingModel,
    private val vectorIndex: LocalVectorIndex,
) {

    /**
     * Runs the full ingestion pipeline for [document] whose raw text has already
     * been extracted by the caller (feature module or data layer PDF parser).
     *
     * @param document  The [OnDeviceDocument] to ingest (must be in PENDING state).
     * @param rawText   Full extracted text of the document.
     * @return Cold [Flow] of [IngestionProgress] events.
     */
    operator fun invoke(document: OnDeviceDocument, rawText: String): Flow<IngestionProgress> = flow {

        // ── 1. Save document record in PENDING state ──────────────────────
        documentRepository.saveDocument(document)

        // ── 2. Mark PROCESSING ────────────────────────────────────────────
        documentRepository.updateStatus(
            id = document.id,
            status = OnDeviceIngestionStatus.PROCESSING,
            failureStage = null,
            totalChunks = 0,
        )
        emit(IngestionProgress.Parsing)

        // ── 3. Chunk ──────────────────────────────────────────────────────
        emit(IngestionProgress.Chunking)
        val chunks = try {
            chunker.chunk(
                text = rawText,
                documentId = document.id,
                documentName = document.fileName,
            )
        } catch (e: Exception) {
            val msg = "Chunking failed: ${e.message}"
            documentRepository.updateStatus(document.id, OnDeviceIngestionStatus.FAILED, "chunking", 0)
            emit(IngestionProgress.Error("chunking", msg))
            return@flow
        }

        if (chunks.isEmpty()) {
            documentRepository.updateStatus(document.id, OnDeviceIngestionStatus.FAILED, "chunking", 0)
            emit(IngestionProgress.Error("chunking", "No chunks produced — document may be empty."))
            return@flow
        }

        // ── 4. Embed + index each chunk ───────────────────────────────────
        if (!embeddingModel.isReady) {
            documentRepository.updateStatus(document.id, OnDeviceIngestionStatus.FAILED, "embedding", 0)
            emit(IngestionProgress.Error("embedding", "Embedding model is not initialised."))
            return@flow
        }

        chunks.forEachIndexed { idx, chunk ->
            emit(IngestionProgress.Embedding(current = idx + 1, total = chunks.size))

            val embedding = try {
                embeddingModel.generateEmbedding(chunk.content)
            } catch (e: Exception) {
                val msg = "Embedding failed at chunk $idx: ${e.message}"
                documentRepository.updateStatus(document.id, OnDeviceIngestionStatus.FAILED, "embedding", idx)
                emit(IngestionProgress.Error("embedding", msg))
                return@flow
            }

            try {
                vectorIndex.addChunk(document.userId, chunk, embedding)
            } catch (e: Exception) {
                val msg = "Vector index write failed at chunk $idx: ${e.message}"
                documentRepository.updateStatus(document.id, OnDeviceIngestionStatus.FAILED, "embedding", idx)
                emit(IngestionProgress.Error("embedding", msg))
                return@flow
            }
        }

        // ── 5. Mark READY ─────────────────────────────────────────────────
        val readyDoc = document.copy(
            ingestionStatus = OnDeviceIngestionStatus.READY,
            totalChunks = chunks.size,
        )
        documentRepository.updateStatus(
            id = document.id,
            status = OnDeviceIngestionStatus.READY,
            failureStage = null,
            totalChunks = chunks.size,
        )
        emit(IngestionProgress.Complete(readyDoc))
    }
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : PdfAgent.kt
 * Purpose    : Agent that handles PDF and document operations by reusing
 *              the existing DocumentRepository upload/ingest/query pipeline.
 *              Hands off to RagAgent for retrieval steps — no duplicate RAG.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Adapter (implements Agent from domain)
 *
 * Key Concepts:
 *   - Does NOT load large PDFs entirely into memory; the URI-based upload
 *     delegates byte reading to DocumentRepositoryImpl which streams from
 *     ContentResolver in chunks
 *   - PDF upload → emit PdfUploading → call DocumentRepository.uploadDocument()
 *     → emit PdfIngesting → poll ingestion status → emit PdfReady
 *   - Query → delegate directly to DocumentRepository.queryDocument() —
 *     same path as existing DocumentChatViewModel.submitQuery()
 *   - No new RAG implementation: reuses the existing pipeline end-to-end
 *
 * Request metadata keys:
 *   "pdf_action"   — "upload" | "query" | "summarize" | "search" (default "query")
 *   "document_id"  — UUID of an already-ingested document (required for query/summarize/search)
 *   "file_uri"     — content:// or file:// URI (required for "upload")
 *   "file_name"    — display name (required for "upload")
 *   "mime_type"    — MIME type (required for "upload"; defaults to "application/pdf")
 *
 * Dependencies: core-common (ApiResult), domain (Agent, DocumentRepository,
 *               AgentCapability, etc.)
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.agent.Agent
import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentError
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentResult
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.model.IngestionStatus
import com.aiassistant.domain.repository.DocumentRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * Agent that handles PDF and document operations.
 *
 * ## Supported actions (`metadata["pdf_action"]`)
 *
 * | Action | Behaviour |
 * |---|---|
 * | `"upload"` | Upload + ingest; emits Thinking events during status polling |
 * | `"query"` | Ask a question about an already-ingested document |
 * | `"summarize"` | Request a plain-language summary of a document |
 * | `"search"` | Full-text search within a document |
 *
 * ## Streaming protocol
 * 1. [AgentEvent.Started]
 * 2. [AgentEvent.StatusChanged] (RUNNING)
 * 3. For upload: [AgentEvent.Thinking] events during ingestion polling
 * 4. [AgentEvent.Token] — answer or upload confirmation
 * 5. [AgentEvent.Completed] or [AgentEvent.Failed]
 */
@Singleton
class PdfAgent @Inject constructor(
    private val documentRepository: DocumentRepository,
) : Agent {

    override val name: String = NAME

    override val description: String =
        "PDF and document processing: upload, ingest, summarize, question answering, " +
            "and document search — reuses existing DocumentRepository pipeline."

    override val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.DOCUMENT_RETRIEVAL,
        AgentCapability.TEXT_GENERATION,
        AgentCapability.STREAMING,
    )

    override fun canHandle(request: AgentRequest): Boolean {
        val explicit = request.metadata[METADATA_AGENT_NAME]
        if (!explicit.isNullOrBlank()) return explicit == name
        // Handle when DOCUMENT_RETRIEVAL is explicitly requested and no other agent has
        // been explicitly named
        return request.capabilities.isEmpty() ||
            AgentCapability.DOCUMENT_RETRIEVAL in request.capabilities
    }

    override fun execute(
        request: AgentRequest,
        execution: AgentExecution,
    ): Flow<AgentEvent> = flow {

        emit(AgentEvent.Started(execution.executionId, name))
        emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))

        val action = request.metadata[METADATA_PDF_ACTION]?.trim()?.lowercase() ?: "query"

        when (action) {
            "upload" -> handleUpload(request, execution)
            "query", "summarize", "search" -> handleQuery(action, request, execution)
            else -> {
                emit(failedEvent(execution, request, "UNKNOWN_ACTION",
                    "Unknown pdf_action '$action'. Supported: upload, query, summarize, search."))
            }
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.handleUpload(
        request: AgentRequest,
        execution: AgentExecution,
    ) {
        val fileUri = request.metadata[METADATA_FILE_URI]?.trim()
        val fileName = request.metadata[METADATA_FILE_NAME]?.trim()
        val mimeType = request.metadata[METADATA_MIME_TYPE]?.trim() ?: "application/pdf"

        if (fileUri.isNullOrBlank()) {
            emit(failedEvent(execution, request, "MISSING_FILE_URI", "metadata['file_uri'] is required for upload."))
            return
        }
        if (fileName.isNullOrBlank()) {
            emit(failedEvent(execution, request, "MISSING_FILE_NAME", "metadata['file_name'] is required for upload."))
            return
        }

        emit(AgentEvent.Thinking(0, "Uploading '$fileName' to document store…"))

        val uploadResult = documentRepository.uploadDocument(fileUri, fileName, mimeType)

        when (uploadResult) {
            is ApiResult.Success -> {
                val document = uploadResult.data
                emit(AgentEvent.Thinking(1, "Upload complete. Ingesting document (id=${document.id})…"))

                // Poll ingestion status (max 30 attempts × 5 s = 150 s budget)
                val ingestionResult = pollIngestion(document.id, execution)
                when (ingestionResult) {
                    IngestionStatus.READY -> {
                        val summary = "Document '${document.fileName}' is ready. " +
                            "Use document_id='${document.id}' to query it."
                        emit(AgentEvent.Token(summary))
                        emit(
                            AgentEvent.Completed(
                                AgentResult(
                                    executionId = execution.executionId,
                                    requestId = request.requestId,
                                    agentName = name,
                                    status = AgentStatus.COMPLETED,
                                    content = summary,
                                    metadata = mapOf(
                                        "document_id" to document.id,
                                        "file_name" to document.fileName,
                                    ),
                                )
                            )
                        )
                    }
                    IngestionStatus.FAILED -> {
                        emit(failedEvent(execution, request, "INGESTION_FAILED",
                            "Document ingestion failed for '${document.fileName}'."))
                    }
                    else -> {
                        emit(failedEvent(execution, request, "INGESTION_TIMEOUT",
                            "Document ingestion timed out — still processing after ${MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000}s."))
                    }
                }
            }
            is ApiResult.Error -> emit(failedEvent(execution, request, "UPLOAD_ERROR", uploadResult.error.message))
            is ApiResult.NetworkUnavailable -> emit(failedEvent(execution, request, "NETWORK_UNAVAILABLE",
                "No network connection. PDF upload requires internet access."))
            is ApiResult.Loading -> emit(failedEvent(execution, request, "UNEXPECTED_STATE", "Unexpected loading state."))
        }
    }

    private suspend fun pollIngestion(documentId: String, execution: AgentExecution): IngestionStatus {
        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            delay(POLL_INTERVAL_MS)
            when (val statusResult = documentRepository.getIngestionStatus(documentId)) {
                is ApiResult.Success -> {
                    val status = statusResult.data
                    Timber.d("PdfAgent: ingestion poll %d/%d — %s", attempt + 1, MAX_POLL_ATTEMPTS, status)
                    if (status == IngestionStatus.READY || status == IngestionStatus.FAILED) {
                        return status
                    }
                }
                else -> { /* continue polling */ }
            }
        }
        return IngestionStatus.PROCESSING // timeout
    }

    // ── Query / Summarize / Search ─────────────────────────────────────────────

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.handleQuery(
        action: String,
        request: AgentRequest,
        execution: AgentExecution,
    ) {
        val documentId = request.metadata[METADATA_DOCUMENT_ID]?.trim()
        if (documentId.isNullOrBlank()) {
            emit(failedEvent(execution, request, "MISSING_DOCUMENT_ID",
                "metadata['document_id'] is required for action '$action'."))
            return
        }

        // Build a query appropriate for the action
        val baseInput = request.input.trim().ifBlank { "Summarize this document." }
        val query = when (action) {
            "summarize" -> "Please provide a comprehensive summary of this document."
            "search" -> baseInput
            else -> baseInput // "query"
        }

        emit(AgentEvent.RetrievalCompleted(query, 0))

        when (val result = documentRepository.queryDocument(documentId, query)) {
            is ApiResult.Success -> {
                emit(AgentEvent.Token(result.data))
                emit(
                    AgentEvent.Completed(
                        AgentResult(
                            executionId = execution.executionId,
                            requestId = request.requestId,
                            agentName = name,
                            status = AgentStatus.COMPLETED,
                            content = result.data,
                            metadata = mapOf(
                                "document_id" to documentId,
                                "action" to action,
                            ),
                        )
                    )
                )
            }
            is ApiResult.Error -> emit(failedEvent(execution, request, "QUERY_ERROR", result.error.message))
            is ApiResult.NetworkUnavailable -> emit(failedEvent(execution, request, "NETWORK_UNAVAILABLE",
                "No network connection. Document queries require internet access."))
            is ApiResult.Loading -> emit(failedEvent(execution, request, "UNEXPECTED_STATE", "Unexpected loading state."))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun failedEvent(execution: AgentExecution, request: AgentRequest, code: String, message: String) =
        AgentEvent.Failed(
            AgentResult(
                executionId = execution.executionId,
                requestId = request.requestId,
                agentName = name,
                status = AgentStatus.FAILED,
                error = AgentError(code = code, message = message),
            )
        )

    companion object {
        const val NAME = "pdf"
        const val METADATA_AGENT_NAME = "agent_name"
        const val METADATA_PDF_ACTION = "pdf_action"
        const val METADATA_DOCUMENT_ID = "document_id"
        const val METADATA_FILE_URI = "file_uri"
        const val METADATA_FILE_NAME = "file_name"
        const val METADATA_MIME_TYPE = "mime_type"

        private const val POLL_INTERVAL_MS = 5_000L
        private const val MAX_POLL_ATTEMPTS = 30
    }
}

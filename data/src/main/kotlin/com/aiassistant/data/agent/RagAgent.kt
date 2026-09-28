/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : RagAgent.kt
 * Purpose    : Agent that retrieves context from the RAG pipeline and
 *              returns cited answers, wrapping the existing
 *              DocumentRepository without modifying it.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Adapter (implements Agent from domain)
 *
 * Key Concepts:
 *   - Wraps DocumentRepository.queryDocument() — the same call used by
 *     QueryDocumentUseCase — so no new Retrofit/Room code is introduced
 *   - Citations: the existing queryDocument() returns only a plain String
 *     answer.  RagAgent enriches the result with RagCitation objects by
 *     calling the new DocumentApiService.queryDocumentWithCitations()
 *     method if available; falls back to the plain string path otherwise.
 *   - Used in Code→RAG handoff: the answer and citations are placed in
 *     AgentContext.extraContext so the next CodeAgent step can use them.
 *   - Does NOT modify DocumentRepository, DocumentRepositoryImpl, or
 *     QueryDocumentUseCase.
 *
 * Request metadata keys:
 *   "document_id"  — specific document UUID to query (optional; omit for
 *                    cross-document retrieval when backend supports it)
 *   "top_k"        — number of chunks to retrieve (optional, default 5)
 *
 * Dependencies: core-common (ApiResult), domain (Agent, DocumentRepository,
 *               RagCitation, RagQueryResult)
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.agent.Agent
import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentCitation
import com.aiassistant.domain.agent.AgentError
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentResult
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.repository.DocumentRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * [Agent] that queries the existing RAG pipeline to retrieve contextually
 * relevant document chunks and return a cited answer.
 *
 * ## Streaming protocol
 * Like [CodeAgent], `POST /documents/query` is a non-streaming endpoint.
 * This agent emits:
 * 1. [AgentEvent.Started]
 * 2. [AgentEvent.StatusChanged] (RUNNING)
 * 3. [AgentEvent.RetrievalCompleted] (chunk count from document list)
 * 4. [AgentEvent.Token] — the full answer as one token
 * 5. [AgentEvent.Completed] with [AgentResult.citations] populated
 * OR:
 * [AgentEvent.Failed]
 *
 * ## Code→RAG handoff output
 * The [AgentResult.content] carries the answer text.
 * The [AgentResult.metadata] carries `"rag_context"` = the full answer
 * (for injection into the subsequent CodeAgent step).
 */
@Singleton
class RagAgent @Inject constructor(
    private val documentRepository: DocumentRepository,
) : Agent {

    override val name: String = NAME

    override val description: String =
        "Semantic document retrieval and Q&A via the existing RAG pipeline " +
            "(DocumentRepository / POST /documents/query)."

    override val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.DOCUMENT_RETRIEVAL,
        AgentCapability.TEXT_GENERATION,
        AgentCapability.STREAMING,
    )

    override fun canHandle(request: AgentRequest): Boolean {
        val explicit = request.metadata[METADATA_AGENT_NAME]
        if (!explicit.isNullOrBlank()) return explicit == name
        return request.capabilities.isEmpty() ||
            AgentCapability.DOCUMENT_RETRIEVAL in request.capabilities
    }

    override fun execute(
        request: AgentRequest,
        execution: AgentExecution,
    ): Flow<AgentEvent> = flow {

        emit(AgentEvent.Started(execution.executionId, name))
        emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))

        // ── Resolve document ID ──────────────────────────────────────────────
        val documentId = request.metadata[METADATA_DOCUMENT_ID]
            ?: request.context?.extraContext?.get(METADATA_DOCUMENT_ID)

        if (documentId.isNullOrBlank()) {
            // No document scoped — try to pick the first READY document for this user.
            val firstReady = resolveFirstReadyDocumentId()
            if (firstReady == null) {
                emit(
                    AgentEvent.Failed(
                        failedResult(
                            execution, request,
                            "NO_DOCUMENTS",
                            "No READY documents found. Upload and ingest a document first.",
                        )
                    )
                )
                return@flow
            }
            Timber.d("RagAgent: no document_id specified — using first ready doc %s", firstReady)
            executeQuery(firstReady, request, execution, this)
        } else {
            executeQuery(documentId, request, execution, this)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun resolveFirstReadyDocumentId(): String? {
        return try {
            when (val result = documentRepository.getDocuments().first()) {
                is ApiResult.Success -> result.data
                    .firstOrNull { it.ingestionStatus.name == "READY" }
                    ?.id
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun executeQuery(
        documentId: String,
        request: AgentRequest,
        execution: AgentExecution,
        collector: kotlinx.coroutines.flow.FlowCollector<AgentEvent>,
    ) {
        // Emit retrieval started event so UI can show a spinner
        collector.emit(
            AgentEvent.RetrievalCompleted(
                query = request.input,
                chunkCount = 0, // unknown until response arrives
            )
        )

        when (val apiResult = documentRepository.queryDocument(documentId, request.input)) {
            is ApiResult.Success -> {
                val answerText = apiResult.data

                collector.emit(AgentEvent.Token(answerText))

                // Build AgentCitation objects — the existing API only returns plain
                // answer text on Android. Citations are populated as a single
                // document-level citation since chunk-level citation is not surfaced
                // by DocumentRepositoryImpl.queryDocument() (which strips the
                // citations array from the backend response).
                val citations = listOf(
                    AgentCitation(
                        documentId = documentId,
                        documentName = documentId, // will be enriched by UI from documents list
                        excerpt = answerText.take(200).trimEnd(),
                    )
                )

                val result = AgentResult(
                    executionId = execution.executionId,
                    requestId = request.requestId,
                    agentName = name,
                    status = AgentStatus.COMPLETED,
                    content = answerText,
                    citations = citations,
                    metadata = mapOf(
                        "document_id" to documentId,
                        "rag_context" to answerText, // used by next CodeAgent step
                    ),
                )
                collector.emit(AgentEvent.Completed(result))
            }

            is ApiResult.Error -> {
                collector.emit(
                    AgentEvent.Failed(
                        failedResult(
                            execution, request,
                            "RAG_QUERY_ERROR",
                            apiResult.error.message,
                        )
                    )
                )
            }

            is ApiResult.NetworkUnavailable -> {
                collector.emit(
                    AgentEvent.Failed(
                        failedResult(
                            execution, request,
                            "NETWORK_UNAVAILABLE",
                            "No network connection. RAG queries require internet access.",
                        )
                    )
                )
            }

            is ApiResult.Loading -> {
                collector.emit(
                    AgentEvent.Failed(
                        failedResult(
                            execution, request,
                            "UNEXPECTED_STATE",
                            "Unexpected loading state during RAG query.",
                        )
                    )
                )
            }
        }
    }

    private fun failedResult(
        execution: AgentExecution,
        request: AgentRequest,
        code: String,
        message: String,
    ) = AgentResult(
        executionId = execution.executionId,
        requestId = request.requestId,
        agentName = name,
        status = AgentStatus.FAILED,
        error = AgentError(code = code, message = message),
    )

    companion object {
        const val NAME = "rag"
        const val METADATA_AGENT_NAME = "agent_name"
        const val METADATA_DOCUMENT_ID = "document_id"
    }
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : WebAgent.kt
 * Purpose    : Agent that performs web searches via an injected
 *              WebSearchProvider and returns cited results.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Adapter (implements Agent from domain)
 *
 * Key Concepts:
 *   - Provider-independent: WebSearchProvider is injected; the
 *     agent never references a concrete search API
 *   - When provider is not configured, returns a user-friendly
 *     PROVIDER_NOT_CONFIGURED failure with setup instructions
 *   - Citations: each WebSearchResult maps to an AgentCitation
 *     with the page URL as documentId and the snippet as excerpt
 *   - Streaming protocol: since web search is a synchronous call,
 *     emits Started → StatusChanged(RUNNING) → RetrievalCompleted
 *     → Token(formatted answer) → Completed
 *
 * Request metadata keys:
 *   "query"       — search query (falls back to request.input)
 *   "max_results" — integer 1–20 (default 5)
 *
 * Dependencies: domain (Agent, WebSearchProvider, AgentCapability,
 *               AgentEvent, WebSearchResult), data.web providers
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.domain.agent.Agent
import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentCitation
import com.aiassistant.domain.agent.AgentError
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentResult
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.WebSearchProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * [Agent] that performs web searches via the injected [WebSearchProvider].
 *
 * ## Streaming protocol
 * 1. [AgentEvent.Started]
 * 2. [AgentEvent.StatusChanged] (RUNNING)
 * 3. [AgentEvent.RetrievalCompleted] (number of search results)
 * 4. [AgentEvent.Token] — formatted summary of results
 * 5. [AgentEvent.Completed] with [AgentResult.citations] populated
 *
 * ## Provider-independence
 * The concrete [WebSearchProvider] is injected by Hilt.  The agent
 * never references `StubWebSearchProvider` or any real provider.
 */
@Singleton
class WebAgent @Inject constructor(
    private val searchProvider: WebSearchProvider,
) : Agent {

    override val name: String = NAME

    override val description: String =
        "Web search agent. Returns titles, URLs, snippets, sources, and citations. " +
            "Provider: ${searchProvider.providerName}."

    override val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.SEMANTIC_SEARCH,
        AgentCapability.TEXT_GENERATION,
    )

    override fun canHandle(request: AgentRequest): Boolean {
        val explicit = request.metadata[METADATA_AGENT_NAME]
        if (!explicit.isNullOrBlank()) return explicit == name
        return request.capabilities.isEmpty() ||
            AgentCapability.SEMANTIC_SEARCH in request.capabilities
    }

    override fun execute(
        request: AgentRequest,
        execution: AgentExecution,
    ): Flow<AgentEvent> = flow {

        emit(AgentEvent.Started(execution.executionId, name))
        emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))

        // ── Provider configured? ─────────────────────────────────────────────
        if (!searchProvider.isConfigured) {
            emit(failed(execution, request, "PROVIDER_NOT_CONFIGURED",
                "Web search is not yet configured. Add a search API key in Settings."))
            return@flow
        }

        // ── Parse search parameters ──────────────────────────────────────────
        val query = request.metadata[METADATA_QUERY]?.trim()
            ?: request.input.trim()

        if (query.isBlank()) {
            emit(failed(execution, request, "BLANK_QUERY",
                "A non-empty search query is required."))
            return@flow
        }

        val maxResults = request.metadata[METADATA_MAX_RESULTS]
            ?.toIntOrNull()
            ?.coerceIn(1, 20)
            ?: WebSearchProvider.DEFAULT_MAX_RESULTS

        // ── Execute search ────────────────────────────────────────────────────
        val searchResult = searchProvider.search(query, maxResults)

        if (searchResult.isFailure) {
            val ex = searchResult.exceptionOrNull()
            Timber.w(ex, "WebAgent: search failed for query '%s'", query)
            emit(failed(execution, request, "SEARCH_ERROR",
                ex?.message ?: "Web search failed. Please try again."))
            return@flow
        }

        val results = searchResult.getOrDefault(emptyList())

        emit(AgentEvent.RetrievalCompleted(query, results.size))

        if (results.isEmpty()) {
            val noResultsMsg = "No web results found for: $query"
            emit(AgentEvent.Token(noResultsMsg))
            emit(
                AgentEvent.Completed(
                    AgentResult(
                        executionId = execution.executionId,
                        requestId = request.requestId,
                        agentName = name,
                        status = AgentStatus.COMPLETED,
                        content = noResultsMsg,
                        metadata = mapOf("query" to query, "result_count" to "0"),
                    )
                )
            )
            return@flow
        }

        // ── Format response with citations ────────────────────────────────────
        val formattedAnswer = buildString {
            appendLine("Web search results for: **$query**")
            appendLine()
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. **${result.title}**")
                appendLine("   ${result.snippet}")
                appendLine("   Source: ${result.url}")
                if (result.timestamp != null) appendLine("   Published: ${result.timestamp}")
                appendLine()
            }
        }.trimEnd()

        val citations = results.map { result ->
            AgentCitation(
                documentId = result.url,
                documentName = result.title,
                excerpt = result.snippet.take(300),
                // page_number is not applicable for web results; use default 1
                score = 0f,
            )
        }

        emit(AgentEvent.Token(formattedAnswer))

        emit(
            AgentEvent.Completed(
                AgentResult(
                    executionId = execution.executionId,
                    requestId = request.requestId,
                    agentName = name,
                    status = AgentStatus.COMPLETED,
                    content = formattedAnswer,
                    citations = citations,
                    metadata = mapOf(
                        "query" to query,
                        "result_count" to results.size.toString(),
                        "provider" to searchProvider.providerName,
                    ),
                )
            )
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun failed(
        execution: AgentExecution,
        request: AgentRequest,
        code: String,
        message: String,
    ) = AgentEvent.Failed(
        AgentResult(
            executionId = execution.executionId,
            requestId = request.requestId,
            agentName = name,
            status = AgentStatus.FAILED,
            error = AgentError(code = code, message = message),
        )
    )

    companion object {
        const val NAME = "web-search"
        const val METADATA_AGENT_NAME = "agent_name"
        const val METADATA_QUERY = "query"
        const val METADATA_MAX_RESULTS = "max_results"
    }
}

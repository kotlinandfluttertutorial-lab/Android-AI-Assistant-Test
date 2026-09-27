/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : CodeAgent.kt
 * Purpose    : Agent implementation that routes code-related requests
 *              through the existing CodeRepository / /code/analyze backend
 *              endpoint without modifying any existing code.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Adapter (implements Agent from domain)
 *
 * Key Concepts:
 *   - Wraps CodeRepository.analyzeCode() — the same call as CodeViewModel
 *   - Supports 6 CodeAgentAction values (3 existing + 3 new)
 *   - Provider-agnostic: provider is forwarded in AgentContext metadata
 *     but not hardcoded — the backend /code/analyze uses AIOrchestrator
 *     which selects provider from settings
 *   - Does NOT modify CodeRepository, CodeRepositoryImpl, or AnalyzeCodeUseCase
 *   - Streaming: because the existing REST endpoint is not a streaming WS,
 *     this agent emits a single AgentEvent.Token with the full content,
 *     then AgentEvent.Completed (consistent with how CodeViewModel works)
 *
 * Dependencies: core-common (ApiResult), domain (Agent, CodeAgentAction,
 *               CodeRepository, CodeAnalysisRequest, CodeAnalysisResult,
 *               SupportedLanguage, CodeAction)
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
import com.aiassistant.domain.agent.CodeAgentAction
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.SupportedLanguage
import com.aiassistant.domain.repository.CodeRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * [Agent] that performs code analysis and generation via the existing
 * [CodeRepository] / `POST /code/analyze` backend endpoint.
 *
 * ## Supported actions (via [AgentRequest.metadata] `"code_action"` key)
 * | Metadata value | Behavior |
 * |---|---|
 * | `"explain"` | Markdown explanation of the submitted code |
 * | `"fix_bug"` | Bug-fixed code with inline `# FIX:` comments |
 * | `"generate_tests"` | Complete runnable test file |
 * | `"generate"` | Generate code from natural-language description |
 * | `"refactor"` | Improve code quality / readability |
 * | `"review"` | Code review report |
 *
 * ## Request metadata keys
 * - `"code_action"` — [CodeAgentAction.apiValue] (required; defaults to `"explain"`)
 * - `"language"` — [SupportedLanguage] lowercase name (optional; defaults to `"kotlin"`)
 *
 * ## Content in [AgentRequest.input]
 * The source code (or natural-language prompt for `"generate"`) to analyse.
 *
 * ## Streaming protocol
 * Since `/code/analyze` is a non-streaming REST endpoint, this agent emits:
 * 1. [AgentEvent.Started]
 * 2. [AgentEvent.StatusChanged] (RUNNING)
 * 3. [AgentEvent.Token] — the full generated content as one token
 * 4. [AgentEvent.Completed] with [AgentResult] carrying `languageId`
 *    and action in metadata
 * OR:
 * [AgentEvent.Failed] on any error path.
 */
@Singleton
class CodeAgent @Inject constructor(
    private val codeRepository: CodeRepository,
) : Agent {

    override val name: String = NAME

    override val description: String =
        "Code analysis, generation, debugging, refactoring, review, and test generation " +
            "via the existing /code/analyze backend endpoint."

    override val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.CODE_ANALYSIS,
        AgentCapability.TEXT_GENERATION,
        AgentCapability.STREAMING,
    )

    override fun canHandle(request: AgentRequest): Boolean {
        val explicit = request.metadata[METADATA_AGENT_NAME]
        if (!explicit.isNullOrBlank()) return explicit == name
        // Handle when CODE_ANALYSIS is requested or no capability constraint
        return request.capabilities.isEmpty() ||
            AgentCapability.CODE_ANALYSIS in request.capabilities
    }

    override fun execute(
        request: AgentRequest,
        execution: AgentExecution,
    ): Flow<AgentEvent> = flow {

        emit(AgentEvent.Started(execution.executionId, name))
        emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))

        // ── Parse action and language from metadata ──────────────────────────
        val actionValue = request.metadata[METADATA_CODE_ACTION]
            ?: request.context?.extraContext?.get(METADATA_CODE_ACTION)
            ?: CodeAgentAction.EXPLAIN.apiValue

        val codeAction = CodeAgentAction.fromApiValue(actionValue)

        val languageName = (
            request.metadata[METADATA_LANGUAGE]
                ?: request.context?.extraContext?.get(METADATA_LANGUAGE)
                ?: SupportedLanguage.KOTLIN.name
            ).uppercase()

        val language = try {
            SupportedLanguage.valueOf(languageName)
        } catch (_: IllegalArgumentException) {
            Timber.w("CodeAgent: unknown language %s — defaulting to KOTLIN", languageName)
            SupportedLanguage.KOTLIN
        }

        // Map CodeAgentAction → existing CodeAction for the first 3 actions;
        // new actions use EXPLAIN as the closest existing backend mapping until
        // the backend is extended in Phase 6.
        val backendAction: CodeAction = when (codeAction) {
            CodeAgentAction.EXPLAIN -> CodeAction.EXPLAIN
            CodeAgentAction.FIX_BUG -> CodeAction.FIX_BUG
            CodeAgentAction.GENERATE_TESTS -> CodeAction.GENERATE_TESTS
            CodeAgentAction.GENERATE -> CodeAction.EXPLAIN   // Phase 6 will add backend support
            CodeAgentAction.REFACTOR -> CodeAction.FIX_BUG  // closest existing action
            CodeAgentAction.REVIEW -> CodeAction.EXPLAIN    // Phase 6 will add backend support
        }

        val analysisRequest = CodeAnalysisRequest(
            code = request.input,
            language = language,
            action = backendAction,
        )

        // ── Invoke existing CodeRepository ───────────────────────────────────
        val apiResult = codeRepository.analyzeCode(analysisRequest)

        when (apiResult) {
            is ApiResult.Success -> {
                val analysisResult = apiResult.data
                // Emit the full content as a single token so the streaming
                // contract is honoured (ChatAgent-compatible).
                emit(AgentEvent.Token(analysisResult.content))

                val result = AgentResult(
                    executionId = execution.executionId,
                    requestId = request.requestId,
                    agentName = name,
                    status = AgentStatus.COMPLETED,
                    content = analysisResult.content,
                    metadata = mapOf(
                        "language_id" to analysisResult.languageId,
                        "action" to codeAction.apiValue,
                        "original_code" to analysisResult.originalCode,
                    ),
                )
                emit(AgentEvent.Completed(result))
            }

            is ApiResult.Error -> {
                val result = AgentResult(
                    executionId = execution.executionId,
                    requestId = request.requestId,
                    agentName = name,
                    status = AgentStatus.FAILED,
                    error = AgentError(
                        code = "CODE_ANALYSIS_ERROR",
                        message = apiResult.error.message,
                    ),
                )
                emit(AgentEvent.Failed(result))
            }

            is ApiResult.NetworkUnavailable -> {
                val result = AgentResult(
                    executionId = execution.executionId,
                    requestId = request.requestId,
                    agentName = name,
                    status = AgentStatus.FAILED,
                    error = AgentError(
                        code = "NETWORK_UNAVAILABLE",
                        message = "No network connection. Code analysis requires internet access.",
                    ),
                )
                emit(AgentEvent.Failed(result))
            }

            is ApiResult.Loading -> {
                // Should not occur in suspend context; treat as transient failure.
                emit(
                    AgentEvent.Failed(
                        AgentResult(
                            executionId = execution.executionId,
                            requestId = request.requestId,
                            agentName = name,
                            status = AgentStatus.FAILED,
                            error = AgentError("UNEXPECTED_STATE", "Unexpected loading state."),
                        )
                    )
                )
            }
        }
    }

    companion object {
        const val NAME = "code-analysis"
        const val METADATA_AGENT_NAME = "agent_name"
        const val METADATA_CODE_ACTION = "code_action"
        const val METADATA_LANGUAGE = "language"
    }
}

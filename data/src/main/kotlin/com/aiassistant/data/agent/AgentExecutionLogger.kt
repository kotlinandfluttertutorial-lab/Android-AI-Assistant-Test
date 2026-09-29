/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : AgentExecutionLogger.kt
 * Purpose    : Structured agent execution logger — emits ObservabilityEvent
 *              instances via ObservabilityEventBus for every significant
 *              agent lifecycle transition.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Facade (wraps ObservabilityEventBus with agent context)
 *
 * Key Concepts:
 *   - Delegates to ObservabilityEventBus.tryEmit() — fire-and-forget, zero allocation
 *     on the hot path, never blocks callers
 *   - Privacy: NEVER logs prompt text, response content, JWT, API keys,
 *     or any user-visible message content
 *   - PiiFilter is applied automatically by the existing ObservabilityEventBus
 *     pipeline downstream — callers do not need to scrub values here
 *   - All log fields map to the structured schema defined in AgentObservabilityRecord
 *   - Metrics (counters/histograms) are carried as metadata keys so the backend
 *     can aggregate them without requiring an Android Prometheus library
 *   - Hilt @Singleton: one instance per process, injected into AgentGateway
 *
 * Phase 9 requirements:
 *   "Add structured logging: request_id, conversation_id, execution_id,
 *    agent, model, status, duration, tool, error"
 *   "Never log: API keys, passwords, JWT secrets, sensitive user content,
 *    model tokens."
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.core.common.observability.EventLevel
import com.aiassistant.core.common.observability.ObservabilityEvent
import com.aiassistant.core.common.observability.ObservabilityEventBus
import com.aiassistant.core.common.observability.SessionManager
import com.aiassistant.domain.agent.AgentError
import com.aiassistant.domain.agent.AgentEventType
import com.aiassistant.domain.agent.AgentLogKey
import com.aiassistant.domain.agent.AgentMode
import com.aiassistant.domain.agent.AgentObservabilityRecord
import com.aiassistant.domain.agent.AgentStatus
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Structured logger for agent execution lifecycle events.
 *
 * Emits [ObservabilityEvent] instances via [ObservabilityEventBus] for:
 * - Execution start / completion / failure / cancellation
 * - Agent-to-agent handoffs
 * - MCP tool starts / completions / failures
 * - LLM request starts / completions with token usage
 * - Routing decisions
 *
 * ## Privacy contract
 * None of the public methods in this class accept content parameters (prompt text,
 * response text, user messages). All parameters are structural identifiers and metrics.
 * This is enforced by design — if a future contributor needs to log content, they must
 * first apply [com.aiassistant.core.common.observability.PiiFilter] and get explicit
 * privacy review.
 *
 * ## Metrics via metadata
 * The [ObservabilityEvent.metadata] map carries counter/histogram data using keys from
 * [AgentLogKey]. The backend aggregates these into Prometheus metrics without requiring
 * an Android metrics library.
 */
@Singleton
class AgentExecutionLogger @Inject constructor(
    private val eventBus: ObservabilityEventBus,
    private val sessionManager: SessionManager,
) {

    // ── Execution lifecycle ───────────────────────────────────────────────────

    /**
     * Log that an agent execution has started.
     *
     * @param requestId      [AgentRequest.requestId].
     * @param executionId    [AgentExecution.executionId].
     * @param agentName      Canonical agent name.
     * @param conversationId Optional conversation UUID.
     * @param model          LLM provider identifier; null if not yet determined.
     * @param mode           Active [AgentMode].
     * @param screen         Compose navigation route that triggered the request.
     */
    fun logExecutionStarted(
        requestId: String,
        executionId: String,
        agentName: String,
        conversationId: String? = null,
        model: String? = null,
        mode: AgentMode = AgentMode.AUTO,
        screen: String? = null,
    ) {
        emit(
            eventType = AgentEventType.AGENT_EXECUTION_STARTED,
            level = EventLevel.INFO,
            message = "Agent execution started: $agentName",
            screen = screen,
            record = AgentObservabilityRecord(
                requestId = requestId,
                executionId = executionId,
                agent = agentName,
                conversationId = conversationId,
                model = model,
                status = AgentStatus.STARTED.name,
                agentMode = mode.name,
            ),
        )
        Timber.d("[AgentLogger] execution_started agent=%s requestId=%s", agentName, requestId)
    }

    /**
     * Log that an agent execution completed successfully.
     *
     * @param requestId      [AgentRequest.requestId].
     * @param executionId    [AgentExecution.executionId].
     * @param agentName      Canonical agent name.
     * @param conversationId Optional conversation UUID.
     * @param model          LLM provider identifier.
     * @param mode           Active [AgentMode].
     * @param durationMs     Total wall-clock duration.
     * @param inputTokens    Input token count from the Done event (0 if unavailable).
     * @param outputTokens   Output token count from the Done event (0 if unavailable).
     * @param screen         Compose navigation route.
     */
    fun logExecutionCompleted(
        requestId: String,
        executionId: String,
        agentName: String,
        conversationId: String? = null,
        model: String? = null,
        mode: AgentMode = AgentMode.AUTO,
        durationMs: Long,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        screen: String? = null,
    ) {
        emit(
            eventType = AgentEventType.AGENT_EXECUTION_COMPLETED,
            level = EventLevel.INFO,
            message = "Agent execution completed: $agentName",
            screen = screen,
            record = AgentObservabilityRecord(
                requestId = requestId,
                executionId = executionId,
                agent = agentName,
                conversationId = conversationId,
                model = model,
                status = AgentStatus.COMPLETED.name,
                durationMs = durationMs,
                agentMode = mode.name,
                additionalMeta = buildMap {
                    if (inputTokens > 0) put(AgentLogKey.INPUT_TOKENS, inputTokens.toString())
                    if (outputTokens > 0) put(AgentLogKey.OUTPUT_TOKENS, outputTokens.toString())
                    val total = inputTokens + outputTokens
                    if (total > 0) put(AgentLogKey.TOTAL_TOKENS, total.toString())
                },
            ),
        )
        Timber.d("[AgentLogger] execution_completed agent=%s duration=%dms", agentName, durationMs)
    }

    /**
     * Log that an agent execution failed.
     *
     * @param requestId      [AgentRequest.requestId].
     * @param executionId    [AgentExecution.executionId].
     * @param agentName      Canonical agent name.
     * @param conversationId Optional conversation UUID.
     * @param mode           Active [AgentMode].
     * @param durationMs     Wall-clock duration until failure.
     * @param error          The [AgentError] carrying a machine-readable code.
     * @param screen         Compose navigation route.
     */
    fun logExecutionFailed(
        requestId: String,
        executionId: String,
        agentName: String,
        conversationId: String? = null,
        mode: AgentMode = AgentMode.AUTO,
        durationMs: Long,
        error: AgentError,
        screen: String? = null,
    ) {
        emit(
            eventType = AgentEventType.AGENT_EXECUTION_FAILED,
            level = EventLevel.ERROR,
            message = "Agent execution failed: $agentName — ${error.code}",
            screen = screen,
            record = AgentObservabilityRecord(
                requestId = requestId,
                executionId = executionId,
                agent = agentName,
                conversationId = conversationId,
                status = AgentStatus.FAILED.name,
                durationMs = durationMs,
                errorCode = error.code,
                agentMode = mode.name,
                // error.message is safe to log — it contains the code-level error
                // string (e.g. "ROUTING_FAILED"), not the user prompt.
                additionalMeta = mapOf(AgentLogKey.ERROR_MESSAGE to error.message.take(200)),
            ),
        )
        Timber.w("[AgentLogger] execution_failed agent=%s code=%s", agentName, error.code)
    }

    /**
     * Log that an agent execution was cancelled.
     */
    fun logExecutionCancelled(
        requestId: String,
        executionId: String,
        agentName: String,
        conversationId: String? = null,
        mode: AgentMode = AgentMode.AUTO,
        durationMs: Long,
        screen: String? = null,
    ) {
        emit(
            eventType = AgentEventType.AGENT_EXECUTION_CANCELLED,
            level = EventLevel.INFO,
            message = "Agent execution cancelled: $agentName",
            screen = screen,
            record = AgentObservabilityRecord(
                requestId = requestId,
                executionId = executionId,
                agent = agentName,
                conversationId = conversationId,
                status = AgentStatus.CANCELLED.name,
                durationMs = durationMs,
                agentMode = mode.name,
            ),
        )
    }

    // ── Handoff ───────────────────────────────────────────────────────────────

    /**
     * Log an agent-to-agent handoff event.
     */
    fun logHandoff(
        requestId: String,
        executionId: String,
        fromAgent: String,
        toAgent: String,
        handoffIndex: Int,
        durationMs: Long,
        screen: String? = null,
    ) {
        emit(
            eventType = AgentEventType.AGENT_HANDOFF,
            level = EventLevel.INFO,
            message = "Handoff: $fromAgent → $toAgent",
            screen = screen,
            record = AgentObservabilityRecord(
                requestId = requestId,
                executionId = executionId,
                agent = fromAgent,
                status = "HANDOFF",
                durationMs = durationMs,
                additionalMeta = mapOf(
                    AgentLogKey.FROM_AGENT    to fromAgent,
                    AgentLogKey.TO_AGENT      to toAgent,
                    AgentLogKey.HANDOFF_INDEX to handoffIndex.toString(),
                ),
            ),
        )
    }

    // ── Tool events ───────────────────────────────────────────────────────────

    /**
     * Log that an MCP tool invocation started.
     */
    fun logToolStarted(
        requestId: String,
        executionId: String,
        agentName: String,
        toolName: String,
        screen: String? = null,
    ) {
        emit(
            eventType = AgentEventType.TOOL_EXECUTION_STARTED,
            level = EventLevel.INFO,
            message = "Tool started: $toolName (agent=$agentName)",
            screen = screen,
            record = AgentObservabilityRecord(
                requestId = requestId,
                executionId = executionId,
                agent = agentName,
                status = "TOOL_STARTED",
                tool = toolName,
            ),
        )
    }

    /**
     * Log that an MCP tool invocation completed successfully.
     */
    fun logToolCompleted(
        requestId: String,
        executionId: String,
        agentName: String,
        toolName: String,
        durationMs: Long,
        screen: String? = null,
    ) {
        emit(
            eventType = AgentEventType.TOOL_EXECUTION_COMPLETED,
            level = EventLevel.INFO,
            message = "Tool completed: $toolName in ${durationMs}ms",
            screen = screen,
            record = AgentObservabilityRecord(
                requestId = requestId,
                executionId = executionId,
                agent = agentName,
                status = "TOOL_COMPLETED",
                tool = toolName,
                durationMs = durationMs,
                additionalMeta = mapOf(AgentLogKey.TOOL_DURATION_MS to durationMs.toString()),
            ),
        )
    }

    /**
     * Log that an MCP tool invocation failed.
     */
    fun logToolFailed(
        requestId: String,
        executionId: String,
        agentName: String,
        toolName: String,
        errorMessage: String,
        screen: String? = null,
    ) {
        emit(
            eventType = AgentEventType.TOOL_EXECUTION_FAILED,
            level = EventLevel.ERROR,
            message = "Tool failed: $toolName",
            screen = screen,
            record = AgentObservabilityRecord(
                requestId = requestId,
                executionId = executionId,
                agent = agentName,
                status = "TOOL_FAILED",
                tool = toolName,
                errorCode = "TOOL_ERROR",
                // errorMessage is the tool's own error string, not user content
                additionalMeta = mapOf(AgentLogKey.ERROR_MESSAGE to errorMessage.take(200)),
            ),
        )
        Timber.w("[AgentLogger] tool_failed tool=%s error=%s", toolName, errorMessage.take(100))
    }

    // ── LLM request ───────────────────────────────────────────────────────────

    /**
     * Log that an LLM streaming request started.
     */
    fun logLlmRequestStarted(
        requestId: String,
        executionId: String,
        agentName: String,
        model: String,
        screen: String? = null,
    ) {
        emit(
            eventType = AgentEventType.LLM_REQUEST_STARTED,
            level = EventLevel.INFO,
            message = "LLM request started: model=$model agent=$agentName",
            screen = screen,
            record = AgentObservabilityRecord(
                requestId = requestId,
                executionId = executionId,
                agent = agentName,
                model = model,
                status = "LLM_STARTED",
            ),
        )
    }

    /**
     * Log that an LLM streaming response completed with token usage.
     *
     * @param inputTokens  Input token count from the Done event.
     * @param outputTokens Output token count from the Done event.
     * @param latencyMs    Time from request start to first token (first-token latency).
     */
    fun logLlmRequestCompleted(
        requestId: String,
        executionId: String,
        agentName: String,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        latencyMs: Long,
        screen: String? = null,
    ) {
        emit(
            eventType = AgentEventType.LLM_REQUEST_COMPLETED,
            level = EventLevel.INFO,
            message = "LLM request completed: model=$model tokens=${inputTokens + outputTokens}",
            screen = screen,
            record = AgentObservabilityRecord(
                requestId = requestId,
                executionId = executionId,
                agent = agentName,
                model = model,
                status = "LLM_COMPLETED",
                durationMs = latencyMs,
                additionalMeta = buildMap {
                    put(AgentLogKey.INPUT_TOKENS, inputTokens.toString())
                    put(AgentLogKey.OUTPUT_TOKENS, outputTokens.toString())
                    put(AgentLogKey.TOTAL_TOKENS, (inputTokens + outputTokens).toString())
                    put(AgentLogKey.LLM_LATENCY_MS, latencyMs.toString())
                },
            ),
        )
    }

    // ── Private emit helper ───────────────────────────────────────────────────

    private fun emit(
        eventType: String,
        level: EventLevel,
        message: String,
        screen: String?,
        record: AgentObservabilityRecord,
    ) {
        val event = ObservabilityEvent(
            timestamp = System.currentTimeMillis(),
            level = level,
            eventType = eventType,
            message = message,
            screen = screen,
            requestId = record.requestId,
            traceId = sessionManager.currentTraceId,
            sessionId = sessionManager.sessionId,
            metadata = record.toMetadata(),
        )
        eventBus.emit(event)
    }
}

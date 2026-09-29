/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentObservability.kt
 * Purpose    : Structured agent-specific EventType constants and the
 *              AgentObservabilityRecord value object used by AgentExecutionLogger.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Value object + constants
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - AgentEventType constants extend the open EventType pattern from
 *     core-common/observability — same open-string design, no enum coupling
 *   - AgentObservabilityRecord carries ALL required log fields for one
 *     agent execution lifecycle event
 *   - Privacy: content fields (prompt, response text) are explicitly
 *     excluded from this record — only structural metadata is logged
 *   - Metrics: reuses ObservabilityEvent.metadata to carry counter/histogram
 *     data so no new Android metrics library is needed
 *
 * Phase 9 requirements:
 *   Structured log fields: request_id, conversation_id, execution_id,
 *   agent, model, status, duration, tool, error
 *   Metrics: agent_execution_total, agent_execution_duration,
 *            agent_error_total, tool_execution_total, tool_error_total,
 *            llm_request_total, llm_latency, token_usage
 *   Never log: API keys, passwords, JWT secrets, sensitive user content,
 *              model tokens.
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

// ── Agent-specific EventType constants ───────────────────────────────────────

/**
 * Agent-specific event type strings for [com.aiassistant.core.common.observability.ObservabilityEvent].
 *
 * Follows the same open-string pattern as `EventType` in core-common — any module
 * may define its own event types without modifying core-common.
 */
object AgentEventType {
    // ── Execution lifecycle ────────────────────────────────────────────────
    /** An agent execution request was submitted to the orchestrator. */
    const val AGENT_EXECUTION_STARTED   = "agent_execution_started"
    /** An agent execution completed successfully (status=COMPLETED or PARTIAL). */
    const val AGENT_EXECUTION_COMPLETED = "agent_execution_completed"
    /** An agent execution failed (status=FAILED). */
    const val AGENT_EXECUTION_FAILED    = "agent_execution_failed"
    /** An agent execution was cancelled by the caller. */
    const val AGENT_EXECUTION_CANCELLED = "agent_execution_cancelled"

    // ── Handoff ────────────────────────────────────────────────────────────
    /** The orchestrator transferred execution from one agent to another. */
    const val AGENT_HANDOFF             = "agent_handoff"

    // ── Tool calls ─────────────────────────────────────────────────────────
    /** An MCP tool invocation started. */
    const val TOOL_EXECUTION_STARTED    = "tool_execution_started"
    /** An MCP tool invocation completed successfully. */
    const val TOOL_EXECUTION_COMPLETED  = "tool_execution_completed"
    /** An MCP tool invocation failed. */
    const val TOOL_EXECUTION_FAILED     = "tool_execution_failed"

    // ── LLM calls ──────────────────────────────────────────────────────────
    /** An LLM streaming request started. */
    const val LLM_REQUEST_STARTED       = "llm_request_started"
    /** An LLM streaming response completed (Done event received). */
    const val LLM_REQUEST_COMPLETED     = "llm_request_completed"
    /** Token usage snapshot (emitted on Done). */
    const val LLM_TOKEN_USAGE           = "llm_token_usage"

    // ── Routing ────────────────────────────────────────────────────────────
    /** The model router resolved a routing decision. */
    const val AGENT_ROUTING_DECISION    = "agent_routing_decision"
}

// ── Metadata key constants ────────────────────────────────────────────────────

/**
 * Well-known metadata keys for agent observability records.
 *
 * All values are plain strings in [com.aiassistant.core.common.observability.ObservabilityEvent.metadata].
 * No PII (prompt content, response text, JWT, API keys) is ever stored here.
 */
object AgentLogKey {
    const val REQUEST_ID        = "request_id"
    const val CONVERSATION_ID   = "conversation_id"
    const val EXECUTION_ID      = "execution_id"
    const val AGENT             = "agent"
    const val MODEL             = "model"
    const val STATUS            = "status"
    const val DURATION_MS       = "duration_ms"
    const val TOOL              = "tool"
    const val ERROR_CODE        = "error_code"
    const val ERROR_MESSAGE     = "error_message"
    const val AGENT_MODE        = "agent_mode"
    const val HANDOFF_INDEX     = "handoff_index"
    const val FROM_AGENT        = "from_agent"
    const val TO_AGENT          = "to_agent"
    const val TOOL_DURATION_MS  = "tool_duration_ms"
    const val INPUT_TOKENS      = "input_tokens"
    const val OUTPUT_TOKENS     = "output_tokens"
    const val TOTAL_TOKENS      = "total_tokens"
    const val LLM_LATENCY_MS    = "llm_latency_ms"
    const val ROUTING_PATH      = "routing_path"
    const val ROUTING_PROVIDER  = "routing_provider"
    const val FALLBACK_OCCURRED = "fallback_occurred"
    const val STEP_INDEX        = "step_index"
}

// ── Structured record ─────────────────────────────────────────────────────────

/**
 * All required log fields for a single agent execution lifecycle event.
 *
 * Passed to [AgentExecutionLogger] to produce an [ObservabilityEvent].
 *
 * ## Privacy guarantee
 * - [AgentObservabilityRecord] intentionally has NO field for prompt text,
 *   response text, conversation content, user PII, API keys, or JWT values.
 * - Only structural metadata (IDs, names, durations, error codes) is included.
 *
 * @param requestId       [AgentRequest.requestId] — correlates with backend.
 * @param conversationId  Conversation UUID (may be null for one-shot tasks).
 * @param executionId     [AgentExecution.executionId] — unique per agent run.
 * @param agent           Canonical agent name (e.g. "conversational", "code-analysis").
 * @param model           LLM provider identifier (e.g. "gemini", "on_device"). Null if N/A.
 * @param status          [AgentStatus] name at the time of this event.
 * @param durationMs      Elapsed wall-clock time since execution start (0 if unknown).
 * @param tool            MCP tool name for tool events; null otherwise.
 * @param errorCode       [AgentError.code] for failure events; null otherwise.
 * @param agentMode       The [AgentMode] that was active when the execution was started.
 * @param additionalMeta  Optional additional metadata (must be PII-free).
 */
@Serializable
data class AgentObservabilityRecord(
    val requestId: String,
    val conversationId: String? = null,
    val executionId: String,
    val agent: String,
    val model: String? = null,
    val status: String,
    val durationMs: Long = 0L,
    val tool: String? = null,
    val errorCode: String? = null,
    val agentMode: String = AgentMode.AUTO.name,
    val additionalMeta: Map<String, String> = emptyMap(),
) {
    /**
     * Converts this record to a flat [Map<String, String>] for use as
     * [com.aiassistant.core.common.observability.ObservabilityEvent.metadata].
     */
    fun toMetadata(): Map<String, String> = buildMap {
        put(AgentLogKey.REQUEST_ID, requestId)
        put(AgentLogKey.EXECUTION_ID, executionId)
        put(AgentLogKey.AGENT, agent)
        put(AgentLogKey.STATUS, status)
        put(AgentLogKey.DURATION_MS, durationMs.toString())
        put(AgentLogKey.AGENT_MODE, agentMode)
        conversationId?.let { put(AgentLogKey.CONVERSATION_ID, it) }
        model?.let { put(AgentLogKey.MODEL, it) }
        tool?.let { put(AgentLogKey.TOOL, it) }
        errorCode?.let { put(AgentLogKey.ERROR_CODE, it) }
        putAll(additionalMeta)
    }
}

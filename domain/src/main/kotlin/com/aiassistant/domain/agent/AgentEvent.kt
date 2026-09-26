/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentEvent.kt
 * Purpose    : Sealed event stream emitted by an Agent during execution.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Sealed class hierarchy (event stream)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Emitted as Flow<AgentEvent> from Agent.execute() so observers can react
 *     to partial progress without waiting for the full AgentResult
 *   - Serialisable for WebSocket transport and local event bus
 *
 * Design Decision:
 *   Events are modelled as a sealed hierarchy (not an enum) because each
 *   event type carries different data.  The ViewModel collects this Flow and
 *   projects it onto a UiState, accumulating tokens into streaming text and
 *   surfacing tool-call progress indicators.
 *
 * Dependencies: kotlinx-serialization-json
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A discrete event emitted by an [Agent] during the lifetime of a single execution.
 *
 * Observers collect [AgentEvent] values from the [Agent.execute] Flow and
 * update their UI accordingly.  The stream always ends with either [Completed],
 * [Failed], or [Cancelled].
 */
@Serializable
sealed class AgentEvent {

    // ── Lifecycle events ────────────────────────────────────────────────────

    /** The agent has accepted the request and is preparing to execute. */
    @Serializable
    @SerialName("started")
    data class Started(
        val executionId: String,
        val agentName: String,
    ) : AgentEvent()

    /**
     * The agent's status has changed.
     *
     * @param executionId  The execution this event belongs to.
     * @param status       The new [AgentStatus].
     */
    @Serializable
    @SerialName("status_changed")
    data class StatusChanged(
        val executionId: String,
        val status: AgentStatus,
    ) : AgentEvent()

    // ── Streaming content events ─────────────────────────────────────────────

    /**
     * A single token of the agent's text response.
     *
     * @param token  The raw text fragment (may be a subword, word, or punctuation).
     */
    @Serializable
    @SerialName("token")
    data class Token(
        val token: String,
    ) : AgentEvent()

    // ── Reasoning / thinking events ──────────────────────────────────────────

    /**
     * The agent has produced an intermediate reasoning step (chain-of-thought).
     * Shown in a "thinking…" UI surface, not in the main response bubble.
     *
     * @param stepIndex  Zero-based index of this reasoning step.
     * @param thought    Text content of the reasoning step.
     */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val stepIndex: Int,
        val thought: String,
    ) : AgentEvent()

    // ── Tool events ──────────────────────────────────────────────────────────

    /**
     * The agent is about to invoke an MCP tool.
     *
     * @param toolName   Name of the tool.
     * @param parameters JSON-serialised input parameters.
     */
    @Serializable
    @SerialName("tool_started")
    data class ToolStarted(
        val toolName: String,
        val parameters: String,
    ) : AgentEvent()

    /**
     * An MCP tool invocation completed successfully.
     *
     * @param toolName  Name of the tool.
     * @param output    JSON-serialised result.
     * @param durationMs Wall-clock duration of the call.
     */
    @Serializable
    @SerialName("tool_completed")
    data class ToolCompleted(
        val toolName: String,
        val output: String,
        val durationMs: Long,
    ) : AgentEvent()

    /**
     * An MCP tool invocation failed.
     *
     * @param toolName      Name of the tool.
     * @param errorMessage  Human-readable error description.
     */
    @Serializable
    @SerialName("tool_failed")
    data class ToolFailed(
        val toolName: String,
        val errorMessage: String,
    ) : AgentEvent()

    /**
     * The agent requires user confirmation before invoking a write-action tool.
     *
     * The execution transitions to WAITING until a confirmation or cancellation
     * is received.
     *
     * @param toolName    The tool that requires confirmation.
     * @param parameters  JSON-serialised parameters shown to the user for review.
     * @param rationale   Why the agent wants to invoke this tool.
     */
    @Serializable
    @SerialName("tool_confirmation_required")
    data class ToolConfirmationRequired(
        val toolName: String,
        val parameters: String,
        val rationale: String? = null,
    ) : AgentEvent()

    // ── Retrieval events ─────────────────────────────────────────────────────

    /**
     * The agent completed a RAG retrieval step.
     *
     * @param query      The retrieval query that was used.
     * @param chunkCount Number of chunks retrieved above the similarity threshold.
     */
    @Serializable
    @SerialName("retrieval_completed")
    data class RetrievalCompleted(
        val query: String,
        val chunkCount: Int,
    ) : AgentEvent()

    // ── Terminal events ──────────────────────────────────────────────────────

    /**
     * The execution finished successfully.
     *
     * @param result  The complete [AgentResult].
     */
    @Serializable
    @SerialName("completed")
    data class Completed(
        val result: AgentResult,
    ) : AgentEvent()

    /**
     * The execution failed with an unrecoverable error.
     *
     * @param result  [AgentResult] with status FAILED and [AgentResult.error] populated.
     */
    @Serializable
    @SerialName("failed")
    data class Failed(
        val result: AgentResult,
    ) : AgentEvent()

    /**
     * The execution was cancelled.
     *
     * @param reason  Optional human-readable reason for cancellation.
     */
    @Serializable
    @SerialName("cancelled")
    data class Cancelled(
        val reason: String = "Cancelled by user.",
    ) : AgentEvent()
}

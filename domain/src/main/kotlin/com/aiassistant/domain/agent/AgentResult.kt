/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentResult.kt
 * Purpose    : Strongly-typed output produced by an Agent execution.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Immutable value object (data class)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Every field that is absent in a partial/failed result is nullable with a
 *     clear default so callers never face NPEs
 *   - Serialisable for persistence and transport
 *
 * Design Decision:
 *   A single flat result type (rather than a sealed Success/Failure hierarchy)
 *   is used here because the AgentStatus field already encodes the terminal
 *   outcome.  The UI layer does: when (result.status) { COMPLETED -> ...,
 *   PARTIAL -> ..., FAILED -> ... } rather than when (result) { is Success ... }.
 *   This keeps the data model thin and avoids nesting.
 *
 * Dependencies: kotlinx-serialization-json
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

/**
 * The complete output of a single [AgentExecution].
 *
 * @param executionId   ID of the [AgentExecution] that produced this result.
 * @param requestId     ID of the originating [AgentRequest].
 * @param agentName     Canonical name of the [Agent] that ran.
 * @param status        Terminal [AgentStatus] at the time this result was produced.
 *                      Always one of: COMPLETED, PARTIAL, FAILED, CANCELLED.
 * @param content       Primary text output.  Null for FAILED/CANCELLED results
 *                      that produced no output.
 * @param toolCalls     All MCP tool invocations made during execution, in order.
 * @param citations     Source citations from RAG retrieval, if any.
 * @param attachments   Binary or structured attachments produced by the agent
 *                      (e.g. generated PDF bytes as Base64, image URL).
 * @param usage         Token consumption breakdown for billing/analytics.
 * @param error         Structured error description when status is FAILED.
 * @param nextAction    Suggested follow-up action the UI can surface to the user.
 * @param metadata      Arbitrary key-value diagnostics (latency, model version, etc.).
 * @param completedAt   Epoch-ms timestamp when the result was finalised.
 */
@Serializable
data class AgentResult(
    val executionId: String,
    val requestId: String,
    val agentName: String,
    val status: AgentStatus,
    val content: String? = null,
    val toolCalls: List<AgentToolCall> = emptyList(),
    val citations: List<AgentCitation> = emptyList(),
    val attachments: List<AgentAttachment> = emptyList(),
    val usage: AgentUsage? = null,
    val error: AgentError? = null,
    val nextAction: AgentNextAction? = null,
    val metadata: Map<String, String> = emptyMap(),
    val completedAt: Long = java.time.Instant.now().toEpochMilli(),
) {
    init {
        require(executionId.isNotBlank()) { "AgentResult.executionId must not be blank." }
        require(requestId.isNotBlank())   { "AgentResult.requestId must not be blank." }
        require(agentName.isNotBlank())   { "AgentResult.agentName must not be blank." }
        require(status.isTerminal) {
            "AgentResult may only be created with a terminal status, got $status."
        }
    }

    /** Returns true when the agent produced usable text content. */
    val hasContent: Boolean get() = !content.isNullOrBlank()

    /** Returns true when the result includes at least one RAG citation. */
    val hasCitations: Boolean get() = citations.isNotEmpty()

    /** Returns true when any tool call failed. */
    val hasToolErrors: Boolean get() = toolCalls.any { it.failed }
}

// ── Supporting value types ──────────────────────────────────────────────────

/**
 * Records a single MCP tool invocation made during agent execution.
 *
 * @param toolName    Name of the invoked tool (e.g. "github", "gmail").
 * @param input       JSON-serialised input parameters.
 * @param output      JSON-serialised tool result, or null if the call failed.
 * @param failed      True when the tool call returned an error.
 * @param errorMessage Human-readable error description when [failed] is true.
 * @param durationMs  Wall-clock time the tool call took.
 */
@Serializable
data class AgentToolCall(
    val toolName: String,
    val input: String,
    val output: String? = null,
    val failed: Boolean = false,
    val errorMessage: String? = null,
    val durationMs: Long = 0L,
)

/**
 * A RAG source citation included in the agent's response.
 *
 * @param documentId   ID of the source document.
 * @param documentName Display name of the source document.
 * @param excerpt      Relevant text excerpt from the chunk.
 * @param pageNumber   Page number within the document, if applicable.
 * @param score        Cosine similarity score of the retrieved chunk (0.0–1.0).
 */
@Serializable
data class AgentCitation(
    val documentId: String,
    val documentName: String,
    val excerpt: String,
    val pageNumber: Int? = null,
    val score: Float = 0f,
)

/**
 * A binary or structured attachment produced by the agent.
 *
 * @param name        Filename or display label (e.g. "resume.pdf").
 * @param mimeType    MIME type (e.g. "application/pdf", "image/png").
 * @param data        Base64-encoded content for small payloads, or null when [uri] is set.
 * @param uri         URI pointing to the stored file for large payloads.
 */
@Serializable
data class AgentAttachment(
    val name: String,
    val mimeType: String,
    val data: String? = null,
    val uri: String? = null,
)

/**
 * Token usage breakdown for a single agent execution.
 *
 * @param inputTokens   Tokens consumed across all LLM input prompts.
 * @param outputTokens  Tokens produced across all LLM completions.
 * @param totalTokens   Sum of input + output tokens.
 * @param estimatedCostUsd Estimated USD cost, or null if pricing data is unavailable.
 */
@Serializable
data class AgentUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int = inputTokens + outputTokens,
    val estimatedCostUsd: Double? = null,
)

/**
 * Structured error detail when [AgentResult.status] is FAILED.
 *
 * @param code    Machine-readable error code (e.g. "TOOL_TIMEOUT", "LLM_QUOTA_EXCEEDED").
 * @param message Human-readable description safe to display in the UI.
 * @param details Optional additional diagnostic information (not shown to the user).
 */
@Serializable
data class AgentError(
    val code: String,
    val message: String,
    val details: String? = null,
)

/**
 * A suggested follow-up action the agent recommends the UI surface to the user.
 *
 * @param type        Category of the action (e.g. "CONFIRM_TOOL_CALL", "RETRY", "OPEN_DOCUMENT").
 * @param label       Short button/chip label (e.g. "Retry", "Open file").
 * @param payload     Serialised payload the UI passes back when the action is triggered.
 */
@Serializable
data class AgentNextAction(
    val type: String,
    val label: String,
    val payload: String? = null,
)

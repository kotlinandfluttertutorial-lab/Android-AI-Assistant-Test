/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentDecision.kt
 * Purpose    : Represents one reasoning step the agent decides to take.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Sealed class hierarchy
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Sealed so the execution loop can exhaustively handle every possible
 *     next action the agent might choose
 *   - Serialisable for step-level audit logging and replay
 *
 * Design Decision:
 *   A sealed class (rather than an enum) is used here because each decision
 *   variant carries distinct data.  The execution loop in AgentExecution uses
 *   a `when (decision)` expression to dispatch to the appropriate handler.
 *
 * Dependencies: kotlinx-serialization-json
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single action the agent has decided to take on a given reasoning step.
 *
 * The agent produces one [AgentDecision] per step.  The execution loop
 * dispatches it, observes the outcome, then calls the agent again (or
 * terminates) based on the result.
 *
 * Variants:
 * - [Respond]   — produce a final or intermediate text response
 * - [CallTool]  — invoke an MCP tool
 * - [Retrieve]  — perform a RAG document retrieval
 * - [Wait]      — pause and await an external signal or user confirmation
 * - [Finish]    — declare execution complete without producing new content
 */
@Serializable
sealed class AgentDecision {

    /**
     * The agent wants to produce a text response to the user.
     *
     * @param content    The text to return.
     * @param isFinal    If true, this is the last response and execution should
     *                   transition to COMPLETED.  If false, more steps follow.
     * @param streaming  If true, the caller should emit tokens incrementally.
     */
    @Serializable
    @SerialName("respond")
    data class Respond(
        val content: String,
        val isFinal: Boolean = true,
        val streaming: Boolean = false,
    ) : AgentDecision()

    /**
     * The agent wants to invoke an MCP tool.
     *
     * @param toolName        Name of the registered [AgentCapability.TOOL_USE] tool.
     * @param parameters      JSON-serialised tool input parameters.
     * @param requiresConfirmation  When true, the execution must pause and surface
     *                        the call to the user for approval before invoking.
     * @param rationale       Optional short explanation of why this tool is being called
     *                        (shown in the "thinking" UI and audit log).
     */
    @Serializable
    @SerialName("call_tool")
    data class CallTool(
        val toolName: String,
        val parameters: String,
        val requiresConfirmation: Boolean = false,
        val rationale: String? = null,
    ) : AgentDecision()

    /**
     * The agent wants to perform a semantic document retrieval step.
     *
     * @param query         The retrieval query (may differ from the original user input).
     * @param documentIds   Optional list of document IDs to restrict retrieval to.
     * @param topK          Number of chunks to retrieve.
     * @param minScore      Minimum cosine similarity threshold for inclusion.
     */
    @Serializable
    @SerialName("retrieve")
    data class Retrieve(
        val query: String,
        val documentIds: List<String> = emptyList(),
        val topK: Int = 5,
        val minScore: Float = 0.4f,
    ) : AgentDecision()

    /**
     * The agent wants to pause and wait for an external signal before proceeding.
     *
     * @param reason       Human-readable reason for waiting (shown in "thinking" UI).
     * @param waitForType  What type of signal to wait for ("user_confirmation", "tool_result").
     * @param payload      Optional context the caller needs when resuming.
     */
    @Serializable
    @SerialName("wait")
    data class Wait(
        val reason: String,
        val waitForType: String = "user_confirmation",
        val payload: String? = null,
    ) : AgentDecision()

    /**
     * The agent has finished all work and there is nothing more to do.
     *
     * @param reason  Optional explanation (stored in metadata).
     */
    @Serializable
    @SerialName("finish")
    data class Finish(
        val reason: String = "Task completed.",
    ) : AgentDecision()
}

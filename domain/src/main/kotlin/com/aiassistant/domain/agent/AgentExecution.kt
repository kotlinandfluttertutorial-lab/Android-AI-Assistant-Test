/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentExecution.kt
 * Purpose    : Tracks the full mutable runtime state of one agent run.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Immutable value object with copy-update helpers
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Immutable by design — every state change returns a new instance via copy()
 *   - withStatus() enforces valid forward transitions using AgentStatus.canTransitionTo()
 *   - Serialisable for persistence (offline queue, crash recovery) and transport
 *
 * Design Decision:
 *   AgentExecution is the "envelope" that wraps a request and accumulates state
 *   as the execution progresses.  It is immutable: the orchestration layer holds
 *   the current instance and replaces it with a new copy on each step.  This
 *   makes the execution history trivially reproducible (just keep a list of
 *   snapshots) and thread-safe by construction.
 *
 * Dependencies: kotlinx-serialization-json, java.time.Instant
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

/**
 * The complete runtime record of a single agent execution.
 *
 * Immutable — use [withStatus], [withStep], [withResult], and [cancel] to
 * produce updated snapshots.
 *
 * @param executionId   Unique ID for this execution run.
 * @param request       The originating [AgentRequest].
 * @param agentName     Canonical name of the [Agent] that was selected.
 * @param status        Current [AgentStatus].
 * @param steps         Ordered list of [AgentStep] records (one per reasoning step).
 * @param result        Final [AgentResult], or null while execution is in progress.
 * @param createdAt     Epoch-ms when the execution was created.
 * @param updatedAt     Epoch-ms of the last status or step update.
 * @param completedAt   Epoch-ms when execution reached a terminal status, or null.
 */
@Serializable
data class AgentExecution(
    val executionId: String = java.util.UUID.randomUUID().toString(),
    val request: AgentRequest,
    val agentName: String,
    val status: AgentStatus = AgentStatus.REQUESTED,
    val steps: List<AgentStep> = emptyList(),
    val result: AgentResult? = null,
    val createdAt: Long = java.time.Instant.now().toEpochMilli(),
    val updatedAt: Long = java.time.Instant.now().toEpochMilli(),
    val completedAt: Long? = null,
) {
    init {
        require(executionId.isNotBlank()) { "AgentExecution.executionId must not be blank." }
        require(agentName.isNotBlank())   { "AgentExecution.agentName must not be blank." }
    }

    // ── Transition helpers ──────────────────────────────────────────────────

    /**
     * Returns a copy of this execution with [status] updated to [newStatus].
     *
     * Enforces the [AgentStatus] state machine: throws [IllegalStateException]
     * for invalid forward transitions.
     *
     * @throws IllegalStateException if the transition from the current status to
     *         [newStatus] is not permitted.
     */
    fun withStatus(newStatus: AgentStatus): AgentExecution {
        check(status.canTransitionTo(newStatus)) {
            "Invalid AgentStatus transition: $status → $newStatus for execution $executionId."
        }
        val now = java.time.Instant.now().toEpochMilli()
        return copy(
            status = newStatus,
            updatedAt = now,
            completedAt = if (newStatus.isTerminal) now else completedAt,
        )
    }

    /**
     * Returns a copy of this execution with [step] appended to [steps].
     *
     * The caller is responsible for ensuring [status] is RUNNING when recording steps.
     */
    fun withStep(step: AgentStep): AgentExecution =
        copy(steps = steps + step, updatedAt = java.time.Instant.now().toEpochMilli())

    /**
     * Returns a copy of this execution with [result] set and status updated to
     * [result.status].
     *
     * The result status must be terminal; [withStatus] transition rules still apply.
     */
    fun withResult(result: AgentResult): AgentExecution =
        withStatus(result.status).copy(result = result)

    /**
     * Returns a copy of this execution in CANCELLED state.
     *
     * Safe to call from any non-terminal state; no-op (returns self) if already terminal.
     */
    fun cancel(): AgentExecution =
        if (status.isTerminal) this else withStatus(AgentStatus.CANCELLED)

    // ── Convenience accessors ───────────────────────────────────────────────

    /** True when the execution has reached a terminal state. */
    val isTerminal: Boolean get() = status.isTerminal

    /** True when the execution produced a successful result (COMPLETED or PARTIAL). */
    val isSuccess: Boolean get() = status.isSuccess

    /** Number of reasoning steps completed so far. */
    val stepCount: Int get() = steps.size

    /** Total tokens consumed across all steps, or 0 if no usage data is available. */
    val totalTokens: Int get() = result?.usage?.totalTokens ?: 0
}

/**
 * Record of a single reasoning or tool-call step within an [AgentExecution].
 *
 * @param stepIndex  Zero-based index of this step within the execution.
 * @param decision   The [AgentDecision] the agent made at this step.
 * @param outcome    JSON-serialised result of executing the decision (tool output,
 *                   retrieved chunks, etc.), or null for Respond/Finish decisions.
 * @param durationMs Wall-clock time this step took.
 * @param tokensUsed Tokens consumed during this step.
 */
@Serializable
data class AgentStep(
    val stepIndex: Int,
    val decision: AgentDecision,
    val outcome: String? = null,
    val durationMs: Long = 0L,
    val tokensUsed: Int = 0,
)

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentStatus.kt
 * Purpose    : Exhaustive lifecycle state enum for an agent execution.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Enum (value type, serialisable)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Serialisable via kotlinx.serialization for persistence and transport
 *   - Every AgentExecution carries exactly one AgentStatus at any point in time
 *
 * Design Decision:
 *   Modelled as an enum rather than a sealed class because each state carries no
 *   additional data — the richer payload lives on AgentExecution.  This keeps
 *   status comparisons and `when` branches O(1) and allocation-free.
 *
 * Dependencies: kotlinx-serialization-json (via domain build.gradle.kts)
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

/**
 * Represents every valid lifecycle state of a single agent execution.
 *
 * State machine (valid forward transitions only):
 *
 * ```
 *                         ┌──────────┐
 *                         │ REQUESTED│
 *                         └────┬─────┘
 *                              │ accepted
 *                         ┌────▼─────┐
 *                         │  STARTED │
 *                         └────┬─────┘
 *                              │ first step begun
 *                         ┌────▼─────┐        ┌──────────┐
 *                         │  RUNNING │◄───────►│  WAITING │
 *                         └────┬─────┘  pause/ └──────────┘
 *               ┌──────────────┼──────────────┐   resume
 *          done │          partial│        error│
 *         ┌─────▼────┐   ┌──────▼───┐   ┌─────▼────┐
 *         │COMPLETED │   │ PARTIAL  │   │  FAILED  │
 *         └──────────┘   └──────────┘   └──────────┘
 *
 *   CANCELLED can be reached from REQUESTED, STARTED, RUNNING, or WAITING.
 * ```
 *
 * @see AgentExecution
 */
@Serializable
enum class AgentStatus {

    /**
     * The agent execution has been submitted and is waiting to be picked up.
     * No steps have been attempted yet.
     */
    REQUESTED,

    /**
     * The execution has been accepted and initial setup is underway (e.g.
     * context assembly, capability check).  No tool calls have been made yet.
     */
    STARTED,

    /**
     * The agent is actively executing steps: calling tools, generating
     * intermediate reasoning, or streaming tokens.
     */
    RUNNING,

    /**
     * Execution is paused waiting for an external signal — either a user
     * confirmation of a write-action tool call, or an async tool response.
     */
    WAITING,

    /**
     * All planned steps finished successfully and a full result is available.
     * This is a terminal state.
     */
    COMPLETED,

    /**
     * The agent produced a result but could not complete all requested steps
     * (e.g. one tool call failed, context limit reached mid-execution).
     * The partial result is still returned.  This is a terminal state.
     */
    PARTIAL,

    /**
     * An unrecoverable error occurred.  The [AgentResult.error] field will
     * contain a [AgentError] describing the failure.  This is a terminal state.
     */
    FAILED,

    /**
     * The execution was cancelled before completion, either by the user or by
     * the system (e.g. timeout, low resources).  This is a terminal state.
     */
    CANCELLED;

    // ── Transition helpers ──────────────────────────────────────────────────

    /** Returns `true` when no further status transitions are possible. */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED

    /** Returns `true` when the execution is considered to have produced usable output. */
    val isSuccess: Boolean
        get() = this == COMPLETED || this == PARTIAL

    /**
     * Returns `true` if transitioning from this status to [next] is a valid forward
     * transition according to the state machine above.
     *
     * Used by [AgentExecution.withStatus] to guard illegal mutations.
     */
    fun canTransitionTo(next: AgentStatus): Boolean = when (this) {
        REQUESTED  -> next == STARTED || next == CANCELLED
        STARTED    -> next == RUNNING || next == WAITING || next == FAILED || next == CANCELLED
        RUNNING    -> next == WAITING || next == COMPLETED || next == PARTIAL ||
                      next == FAILED || next == CANCELLED
        WAITING    -> next == RUNNING || next == FAILED || next == CANCELLED
        COMPLETED  -> false
        PARTIAL    -> false
        FAILED     -> false
        CANCELLED  -> false
    }
}

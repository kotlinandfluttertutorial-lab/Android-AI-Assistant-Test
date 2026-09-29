/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SafetyLimits.kt
 * Purpose    : Named configuration for all safety limits that guard against
 *              infinite loops, recursive handoffs, runaway tool execution,
 *              and token budget overruns.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Immutable value object (config)
 *
 * Key Concepts:
 *   - Single source of truth for all safety limits used by AgentPlan,
 *     DefaultAgentOrchestrator, and DefaultAgentPlanner
 *   - Caller supplies a SafetyLimits to override defaults at request time
 *   - Hard limits (HARD_*) cannot be overridden — they are the absolute ceiling
 *   - All fields validated at construction (fail-fast)
 *   - Pure Kotlin, zero Android/framework dependencies
 *
 * Phase 8 requirement: "Configure MAX_AGENT_STEPS, MAX_AGENT_HANDOFFS,
 *   MAX_TOOL_CALLS, AGENT_TIMEOUT, MAX_CONTEXT_TOKENS, MAX_OUTPUT_TOKENS"
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

/**
 * Immutable configuration for all safety limits that prevent unbounded
 * autonomous agent execution.
 *
 * ## Limit hierarchy
 * Defaults (soft) → Request override → Hard caps (absolute ceiling)
 *
 * ## Usage
 * ```kotlin
 * val strict = SafetyLimits(
 *     maxAgentSteps = 5,
 *     maxAgentHandoffs = 2,
 *     maxToolCalls = 10,
 *     agentTimeoutMs = 30_000L,
 *     maxContextTokens = 4_096,
 *     maxOutputTokens = 512,
 * )
 * ```
 *
 * @param maxAgentSteps     Maximum reasoning / tool-call steps per agent invocation.
 * @param maxAgentHandoffs  Maximum agent-to-agent handoffs in one plan.
 * @param maxToolCalls      Maximum total MCP tool calls across all plan steps.
 * @param agentTimeoutMs    Wall-clock timeout for the entire plan (milliseconds).
 * @param maxContextTokens  Maximum tokens allowed in the assembled context window.
 *                          0 = unlimited (no token trimming).
 * @param maxOutputTokens   Maximum tokens allowed in any single agent response.
 *                          0 = unlimited.
 */
@Serializable
data class SafetyLimits(
    val maxAgentSteps: Int = DEFAULT_MAX_AGENT_STEPS,
    val maxAgentHandoffs: Int = DEFAULT_MAX_AGENT_HANDOFFS,
    val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    val agentTimeoutMs: Long = DEFAULT_AGENT_TIMEOUT_MS,
    val maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
) {
    init {
        require(maxAgentSteps in 1..HARD_MAX_AGENT_STEPS) {
            "SafetyLimits.maxAgentSteps must be in 1..${HARD_MAX_AGENT_STEPS}, was $maxAgentSteps."
        }
        require(maxAgentHandoffs in 0..HARD_MAX_AGENT_HANDOFFS) {
            "SafetyLimits.maxAgentHandoffs must be in 0..${HARD_MAX_AGENT_HANDOFFS}, was $maxAgentHandoffs."
        }
        require(maxToolCalls in 0..HARD_MAX_TOOL_CALLS) {
            "SafetyLimits.maxToolCalls must be in 0..${HARD_MAX_TOOL_CALLS}, was $maxToolCalls."
        }
        require(agentTimeoutMs > 0) {
            "SafetyLimits.agentTimeoutMs must be positive, was $agentTimeoutMs."
        }
        require(maxContextTokens >= 0) {
            "SafetyLimits.maxContextTokens must be non-negative, was $maxContextTokens."
        }
        require(maxOutputTokens >= 0) {
            "SafetyLimits.maxOutputTokens must be non-negative, was $maxOutputTokens."
        }
    }

    /**
     * Returns a new [SafetyLimits] clamped so no value exceeds the hard caps.
     * Useful when a caller provides values that are close to but not yet over limits.
     */
    fun clampToHardLimits(): SafetyLimits = SafetyLimits(
        maxAgentSteps = maxAgentSteps.coerceAtMost(HARD_MAX_AGENT_STEPS),
        maxAgentHandoffs = maxAgentHandoffs.coerceAtMost(HARD_MAX_AGENT_HANDOFFS),
        maxToolCalls = maxToolCalls.coerceAtMost(HARD_MAX_TOOL_CALLS),
        agentTimeoutMs = agentTimeoutMs.coerceAtMost(HARD_MAX_AGENT_TIMEOUT_MS),
        maxContextTokens = maxContextTokens,
        maxOutputTokens = maxOutputTokens,
    )

    /**
     * Convert this [SafetyLimits] to [AgentPlan] constructor parameters.
     *
     * Bridges the named-config surface to the existing [AgentPlan] type so
     * callers can pass a [SafetyLimits] without knowing [AgentPlan] internals.
     */
    fun toAgentPlanLimits() = AgentPlanLimits(
        maxSteps = maxAgentSteps,
        maxHandoffs = maxAgentHandoffs,
        maxToolCalls = maxToolCalls,
        timeoutMs = agentTimeoutMs,
    )

    companion object {
        // ── Soft defaults (overridable per-request) ───────────────────────
        const val DEFAULT_MAX_AGENT_STEPS: Int = 10
        const val DEFAULT_MAX_AGENT_HANDOFFS: Int = 3
        const val DEFAULT_MAX_TOOL_CALLS: Int = 20
        const val DEFAULT_AGENT_TIMEOUT_MS: Long = 120_000L   // 2 minutes
        const val DEFAULT_MAX_CONTEXT_TOKENS: Int = 8_192
        const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 2_048

        // ── Hard caps (absolute ceiling — cannot be overridden) ────────────
        const val HARD_MAX_AGENT_STEPS: Int = 50
        const val HARD_MAX_AGENT_HANDOFFS: Int = 10
        const val HARD_MAX_TOOL_CALLS: Int = 100
        const val HARD_MAX_AGENT_TIMEOUT_MS: Long = 600_000L  // 10 minutes

        /** Permissive limits for testing — do not use in production. */
        val TESTING: SafetyLimits = SafetyLimits(
            maxAgentSteps = 50,
            maxAgentHandoffs = 10,
            maxToolCalls = 100,
            agentTimeoutMs = 10_000L,
            maxContextTokens = 0,   // unlimited in tests
            maxOutputTokens = 0,
        )

        /** Strict limits for high-risk contexts. */
        val STRICT: SafetyLimits = SafetyLimits(
            maxAgentSteps = 3,
            maxAgentHandoffs = 1,
            maxToolCalls = 5,
            agentTimeoutMs = 30_000L,
            maxContextTokens = 4_096,
            maxOutputTokens = 512,
        )
    }
}

/**
 * Bridge type: the subset of [SafetyLimits] that maps directly to [AgentPlan]
 * constructor fields. Used internally by [DefaultAgentOrchestrator] to build
 * plans from a [SafetyLimits] instance.
 *
 * @see SafetyLimits.toAgentPlanLimits
 */
data class AgentPlanLimits(
    val maxSteps: Int,
    val maxHandoffs: Int,
    val maxToolCalls: Int,
    val timeoutMs: Long,
)

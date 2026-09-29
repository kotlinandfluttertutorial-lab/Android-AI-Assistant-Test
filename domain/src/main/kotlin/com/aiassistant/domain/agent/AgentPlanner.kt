/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentPlanner.kt
 * Purpose    : Builds and validates multi-step execution plans with hard limits.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Value object + interface + default implementation
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Plans are validated before execution begins; limits are enforced during
 *     execution by AgentOrchestrator which calls checkLimits()
 *   - Hard limits prevent runaway autonomous behavior:
 *       maxSteps     — max reasoning steps per execution
 *       maxHandoffs  — max agent-to-agent handoffs in one plan
 *       maxToolCalls — max MCP tool invocations in one plan
 *       timeoutMs    — wall-clock budget for the whole plan
 *
 * Design Decision:
 *   Plans are immutable value objects. DefaultAgentPlanner is a pure function;
 *   it never calls an LLM to produce a plan. Plans are either single-agent
 *   (most common) or multi-step chains declared explicitly by the caller via
 *   AgentRequest.metadata["plan"].
 *
 * Dependencies: domain agent models
 * ============================================================
 */

package com.aiassistant.domain.agent

/**
 * A single step in an [AgentPlan].
 *
 * @param agentName  Name of the [Agent] to invoke for this step.
 * @param inputTransform Optional label describing how the previous step's
 *                       output is transformed before being passed as input
 *                       (e.g. "pdf_text_to_rag_query"). Informational only.
 */
data class AgentPlanStep(
    val agentName: String,
    val inputTransform: String = "",
) {
    init {
        require(agentName.isNotBlank()) { "AgentPlanStep.agentName must not be blank." }
    }
}

/**
 * An immutable, validated execution plan for an [AgentRequest].
 *
 * A plan contains one or more [AgentPlanStep] entries executed sequentially.
 * After each step the output is optionally transformed and passed as input
 * to the next step (handoff).
 *
 * @param steps         Ordered list of steps (at least one).
 * @param maxSteps      Hard cap on reasoning iterations per agent invocation.
 * @param maxHandoffs   Hard cap on agent-to-agent handoffs in this plan.
 * @param maxToolCalls  Hard cap on total MCP tool calls across all steps.
 * @param timeoutMs     Wall-clock budget for the entire plan (ms).
 * @param requestId     The originating [AgentRequest.requestId].
 */
data class AgentPlan(
    val steps: List<AgentPlanStep>,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val maxHandoffs: Int = DEFAULT_MAX_HANDOFFS,
    val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val requestId: String,
) {
    init {
        require(steps.isNotEmpty()) { "AgentPlan must contain at least one step." }
        require(requestId.isNotBlank()) { "AgentPlan.requestId must not be blank." }
        require(maxSteps in 1..HARD_LIMIT_MAX_STEPS) {
            "AgentPlan.maxSteps must be in 1..${HARD_LIMIT_MAX_STEPS}, was $maxSteps."
        }
        require(maxHandoffs in 0..HARD_LIMIT_MAX_HANDOFFS) {
            "AgentPlan.maxHandoffs must be in 0..${HARD_LIMIT_MAX_HANDOFFS}, was $maxHandoffs."
        }
        require(maxToolCalls in 0..HARD_LIMIT_MAX_TOOL_CALLS) {
            "AgentPlan.maxToolCalls must be in 0..${HARD_LIMIT_MAX_TOOL_CALLS}, was $maxToolCalls."
        }
        require(timeoutMs > 0) { "AgentPlan.timeoutMs must be positive, was $timeoutMs." }
        require(steps.size - 1 <= maxHandoffs) {
            "AgentPlan has ${steps.size} steps implying ${steps.size - 1} handoffs, " +
                "but maxHandoffs=$maxHandoffs."
        }
    }

    /** True when this plan has more than one step (requires at least one handoff). */
    val isMultiStep: Boolean get() = steps.size > 1

    /** Number of agent-to-agent handoffs this plan requires. */
    val handoffCount: Int get() = (steps.size - 1).coerceAtLeast(0)

    companion object {
        const val DEFAULT_MAX_STEPS: Int = 10
        const val DEFAULT_MAX_HANDOFFS: Int = 3
        const val DEFAULT_MAX_TOOL_CALLS: Int = 20
        const val DEFAULT_TIMEOUT_MS: Long = 120_000L

        // Hard upper limits — plans exceeding these are rejected at construction
        const val HARD_LIMIT_MAX_STEPS: Int = 50
        const val HARD_LIMIT_MAX_HANDOFFS: Int = 10
        const val HARD_LIMIT_MAX_TOOL_CALLS: Int = 100
    }
}

/**
 * Runtime limit violations detected during plan execution.
 *
 * Produced by [AgentPlanner.checkLimits] at each execution step.
 */
sealed class LimitViolation {
    /** The plan's [AgentPlan.maxSteps] would be exceeded by the next step. */
    data class MaxStepsExceeded(val current: Int, val limit: Int) : LimitViolation()

    /** The plan's [AgentPlan.maxHandoffs] would be exceeded. */
    data class MaxHandoffsExceeded(val current: Int, val limit: Int) : LimitViolation()

    /** The plan's [AgentPlan.maxToolCalls] would be exceeded. */
    data class MaxToolCallsExceeded(val current: Int, val limit: Int) : LimitViolation()

    /** The plan's [AgentPlan.timeoutMs] has been exhausted. */
    data class TimeoutExceeded(val elapsedMs: Long, val limitMs: Long) : LimitViolation()
}

/**
 * Snapshot of execution counters used by [AgentPlanner.checkLimits].
 *
 * @param stepsTaken    Steps completed so far.
 * @param handoffsDone  Agent-to-agent handoffs completed so far.
 * @param toolCallsMade MCP tool calls completed so far.
 * @param elapsedMs     Wall-clock time elapsed since plan start.
 */
data class PlanCounters(
    val stepsTaken: Int = 0,
    val handoffsDone: Int = 0,
    val toolCallsMade: Int = 0,
    val elapsedMs: Long = 0L,
)

/**
 * Builds [AgentPlan]s from [AgentRequest]s and checks runtime limits.
 */
interface AgentPlanner {

    /**
     * Build a plan for [request] using [registry] to validate agent names.
     *
     * For simple requests (no multi-step hint in metadata) this returns a
     * single-step plan targeting the agent already selected by [AgentRouter].
     *
     * For multi-step requests (metadata key `"plan_steps"` contains a
     * comma-separated list of agent names) this builds a chained plan after
     * validating each agent exists in [registry].
     *
     * @param request      The request to plan for.
     * @param selectedAgent The agent already chosen by [AgentRouter] for the
     *                      first step.
     * @param registry     Registry used to validate multi-step agent names.
     * @return A validated [AgentPlan].
     * @throws IllegalArgumentException if a named agent is not registered or
     *         the plan violates hard limits.
     */
    fun buildPlan(
        request: AgentRequest,
        selectedAgent: Agent,
        registry: AgentRegistry,
    ): AgentPlan

    /**
     * Check whether [counters] would violate [plan]'s limits on the *next*
     * step.
     *
     * Called by [AgentOrchestrator] before executing each step.
     *
     * @return The first [LimitViolation] detected, or `null` if all limits
     *         are within budget.
     */
    fun checkLimits(plan: AgentPlan, counters: PlanCounters): LimitViolation?
}

// ── Default implementation ───────────────────────────────────────────────────

/** Metadata key for declaring a multi-step plan in the request. */
const val METADATA_KEY_PLAN_STEPS = "plan_steps"

/**
 * Default [AgentPlanner] — deterministic, no LLM calls.
 */
class DefaultAgentPlanner : AgentPlanner {

    override fun buildPlan(
        request: AgentRequest,
        selectedAgent: Agent,
        registry: AgentRegistry,
    ): AgentPlan {
        val planStepsHint = request.metadata[METADATA_KEY_PLAN_STEPS]

        // ── Multi-step plan declared by caller ──────────────────────────────
        if (!planStepsHint.isNullOrBlank()) {
            val agentNames = planStepsHint
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            require(agentNames.isNotEmpty()) {
                "metadata['$METADATA_KEY_PLAN_STEPS'] must contain at least one agent name."
            }

            // Validate all agent names exist in the registry
            agentNames.forEach { name ->
                registry.get(name) // throws AgentNotFoundException if missing
            }

            val steps = agentNames.map { AgentPlanStep(agentName = it) }
            return AgentPlan(
                steps = steps,
                maxSteps = request.maxSteps,
                maxHandoffs = (agentNames.size - 1).coerceAtLeast(0),
                maxToolCalls = AgentPlan.DEFAULT_MAX_TOOL_CALLS,
                timeoutMs = request.timeoutMs,
                requestId = request.requestId,
            )
        }

        // ── Single-step plan (most common path) ─────────────────────────────
        return AgentPlan(
            steps = listOf(AgentPlanStep(agentName = selectedAgent.name)),
            maxSteps = request.maxSteps,
            maxHandoffs = 0,
            maxToolCalls = AgentPlan.DEFAULT_MAX_TOOL_CALLS,
            timeoutMs = request.timeoutMs,
            requestId = request.requestId,
        )
    }

    override fun checkLimits(plan: AgentPlan, counters: PlanCounters): LimitViolation? {
        // Check steps — the *next* step would take stepsTaken to stepsTaken+1
        if (counters.stepsTaken >= plan.maxSteps) {
            return LimitViolation.MaxStepsExceeded(counters.stepsTaken, plan.maxSteps)
        }
        // Phase 8 fix: the previous condition was a tautology. Correct check:
        // handoffs already done must not exceed the plan's maxHandoffs limit.
        if (counters.handoffsDone > plan.maxHandoffs) {
            return LimitViolation.MaxHandoffsExceeded(counters.handoffsDone, plan.maxHandoffs)
        }
        if (counters.toolCallsMade >= plan.maxToolCalls) {
            return LimitViolation.MaxToolCallsExceeded(counters.toolCallsMade, plan.maxToolCalls)
        }
        if (counters.elapsedMs >= plan.timeoutMs) {
            return LimitViolation.TimeoutExceeded(counters.elapsedMs, plan.timeoutMs)
        }
        return null
    }
}

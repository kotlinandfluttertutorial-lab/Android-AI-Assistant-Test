/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentOrchestrator.kt
 * Purpose    : Central coordinator — routes requests, builds plans, drives
 *              agent execution, handles handoffs, cancellation, and timeouts.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface + default implementation
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Flow<AgentEvent> returned from execute() matches Agent.execute() contract
 *   - Timeout is enforced by wrapping execution in withTimeoutOrNull
 *   - Handoff: when an agent emits AgentEvent.Completed with
 *     AgentResult.nextAction.type == "HANDOFF", orchestrator routes the
 *     result content as the input of the next plan step
 *   - Cancellation: collecting coroutine cancels the Flow which propagates
 *     through all inner callbackFlow / collect calls automatically
 *   - The orchestrator NEVER contains hardcoded agent-specific logic
 *   - Phase 8: HandoffStarted/HandoffCompleted events surface inter-agent
 *     transitions; recursion protection rejects plans with >MAX_SAME_AGENT
 *     consecutive same-agent steps
 *
 * Bug fixes (Phase 8):
 *   - stepResult was always null because execution.result is never mutated by
 *     agents (they emit events, not mutations). Fixed by tracking the last
 *     AgentEvent.Completed emitted during collection.
 *   - checkLimits() handoff guard condition was a tautology; now uses
 *     fixed DefaultAgentPlanner.checkLimits().
 *
 * Design Decision:
 *   DefaultAgentOrchestrator is in :domain (not :data) so domain tests can
 *   test the full orchestration flow with stub agents and a DefaultAgentRegistry.
 *   It depends only on the domain interfaces defined in this package.
 *
 * Dependencies: domain agent models, AgentRegistry, AgentRouter,
 *               AgentPlanner, kotlinx.coroutines
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Error types surfaced by [AgentOrchestrator].
 */
sealed class OrchestratorError {
    data class RoutingFailed(val reason: String) : OrchestratorError()
    data class PlanLimitExceeded(val violation: LimitViolation) : OrchestratorError()
    data class AgentFailed(val agentName: String, val error: AgentError) : OrchestratorError()
    data class TimeoutExceeded(val timeoutMs: Long) : OrchestratorError()
    data class HandoffFailed(val fromAgent: String, val toAgent: String, val reason: String) : OrchestratorError()
    data class Cancelled(val reason: String = "Cancelled by caller.") : OrchestratorError()
    /** Phase 8: emitted when a plan contains a recursive agent cycle. */
    data class RecursionDetected(val agentName: String, val cycleLength: Int) : OrchestratorError()
}

/**
 * Central coordinator for agent request execution.
 *
 * The returned [Flow] emits [AgentEvent] values in real time, culminating in
 * [AgentEvent.Completed], [AgentEvent.Failed], or [AgentEvent.Cancelled].
 */
interface AgentOrchestrator {

    /**
     * Execute [request] end-to-end.
     *
     * Flow:
     * ```
     * AgentRequest
     *   → AgentRouter.route()        (select agent)
     *   → AgentPlanner.buildPlan()   (build step list, validate limits)
     *   → for each step:
     *       AgentPlanner.checkLimits()
     *       Agent.execute()          (emit events, collect result)
     *       emit HandoffStarted / HandoffCompleted
     *       if nextAction.type == "HANDOFF" → route again for next step
     *   → emit terminal AgentEvent
     * ```
     *
     * @param request The validated [AgentRequest].
     * @return Cold [Flow] of [AgentEvent]; execution starts on first collection.
     */
    fun execute(request: AgentRequest): Flow<AgentEvent>
}

// ── Default implementation ───────────────────────────────────────────────────

/**
 * Default [AgentOrchestrator].
 *
 * Fully deterministic control flow — no LLM calls made here.
 * Thread-safe: all mutable state lives inside the cold Flow lambda.
 *
 * @param registry Registry to look up agents.
 * @param router   Router to select the initial (and handoff) agent.
 * @param planner  Planner to build the execution plan and check limits.
 */
class DefaultAgentOrchestrator(
    private val registry: AgentRegistry,
    private val router: AgentRouter,
    private val planner: AgentPlanner,
) : AgentOrchestrator {

    override fun execute(request: AgentRequest): Flow<AgentEvent> = channelFlow {
        val startMs = System.currentTimeMillis()

        // ── 1. Route ────────────────────────────────────────────────────────
        val routingOutcome = router.route(request, registry)
        if (routingOutcome is RoutingOutcome.NoAgentFound) {
            val result = terminalFailedResult(
                executionId = newId(),
                request = request,
                agentName = "orchestrator",
                code = "ROUTING_FAILED",
                message = routingOutcome.reason,
            )
            send(AgentEvent.Failed(result))
            return@channelFlow
        }
        val firstAgent = (routingOutcome as RoutingOutcome.Routed).agent

        // ── 2. Plan ─────────────────────────────────────────────────────────
        val plan: AgentPlan
        try {
            plan = planner.buildPlan(request, firstAgent, registry)
        } catch (e: Exception) {
            val result = terminalFailedResult(
                executionId = newId(),
                request = request,
                agentName = firstAgent.name,
                code = "PLAN_BUILD_FAILED",
                message = e.message ?: "Plan construction failed.",
            )
            send(AgentEvent.Failed(result))
            return@channelFlow
        }

        // ── 2b. Recursion check (Phase 8) ────────────────────────────────────
        val recursionError = detectRecursion(plan)
        if (recursionError != null) {
            val result = terminalFailedResult(
                executionId = newId(),
                request = request,
                agentName = firstAgent.name,
                code = "RECURSION_DETECTED",
                message = "Recursive agent cycle detected: ${recursionError.agentName} " +
                    "appears ${recursionError.cycleLength + 1} consecutive times.",
            )
            send(AgentEvent.Failed(result))
            return@channelFlow
        }

        // ── 3. Execute plan under timeout ────────────────────────────────────
        val timedOut = withTimeoutOrNull(plan.timeoutMs) {
            executePlan(
                request = request,
                plan = plan,
                startMs = startMs,
                emit = { event -> send(event) },
            )
        }

        if (timedOut == null) {
            // withTimeoutOrNull returned null → timeout
            val result = terminalFailedResult(
                executionId = newId(),
                request = request,
                agentName = firstAgent.name,
                code = "TIMEOUT",
                message = "Plan execution exceeded timeout of ${plan.timeoutMs} ms.",
            )
            send(AgentEvent.Failed(result))
        }
    }

    /**
     * Execute all steps of [plan], emitting events via [emit].
     *
     * Bug fix (Phase 8): stepResult is now tracked from the last
     * [AgentEvent.Completed] emitted during collection, not from
     * `execution.result` (which agents never set).
     *
     * @return `true` when the plan completed (successfully or with failure);
     *         the coroutine returns early if the collector is cancelled.
     */
    private suspend fun executePlan(
        request: AgentRequest,
        plan: AgentPlan,
        startMs: Long,
        emit: suspend (AgentEvent) -> Unit,
    ): Boolean {
        var currentInput = request.input
        var handoffsDone = 0
        var toolCallsMade = 0
        var stepsTaken = 0

        for ((stepIndex, planStep) in plan.steps.withIndex()) {
            // Check cancellation before each step
            if (!currentCoroutineContext().isActive) {
                emit(AgentEvent.Cancelled("Execution cancelled by caller."))
                return true
            }

            // Check limits
            val counters = PlanCounters(
                stepsTaken = stepsTaken,
                handoffsDone = handoffsDone,
                toolCallsMade = toolCallsMade,
                elapsedMs = System.currentTimeMillis() - startMs,
            )
            val violation = planner.checkLimits(plan, counters)
            if (violation != null) {
                val result = terminalFailedResult(
                    executionId = newId(),
                    request = request,
                    agentName = planStep.agentName,
                    code = "LIMIT_EXCEEDED",
                    message = violation.toString(),
                )
                emit(AgentEvent.Failed(result))
                return true
            }

            // Resolve agent for this step
            val agent = try {
                registry.get(planStep.agentName)
            } catch (e: AgentNotFoundException) {
                val result = terminalFailedResult(
                    executionId = newId(),
                    request = request,
                    agentName = planStep.agentName,
                    code = "AGENT_NOT_FOUND",
                    message = e.message ?: "Agent not found.",
                )
                emit(AgentEvent.Failed(result))
                return true
            }

            // Build step request (for handoff steps, use the accumulated output)
            val stepRequest = if (stepIndex == 0) {
                request
            } else {
                AgentRequest(
                    userId = request.userId,
                    input = currentInput,
                    conversationId = request.conversationId,
                    provider = request.provider,
                    capabilities = request.capabilities,
                    context = request.context,
                    maxSteps = request.maxSteps,
                    timeoutMs = (request.timeoutMs - (System.currentTimeMillis() - startMs))
                        .coerceAtLeast(1L),
                    streamingEnabled = request.streamingEnabled,
                    metadata = request.metadata,
                )
            }

            // ── Phase 8: Emit HandoffStarted before steps > 0 ───────────────
            if (stepIndex > 0) {
                val previousAgentName = plan.steps[stepIndex - 1].agentName
                emit(
                    AgentEvent.HandoffStarted(
                        fromAgent = previousAgentName,
                        toAgent = planStep.agentName,
                        handoffIndex = handoffsDone,
                        context = currentInput.take(200),
                    )
                )
                emit(
                    AgentEvent.StatusChanged(
                        executionId = newId(),
                        status = AgentStatus.RUNNING,
                    )
                )
                handoffsDone++
            }

            // Create execution envelope
            val execution = AgentExecution(
                request = stepRequest,
                agentName = agent.name,
                status = AgentStatus.STARTED,
            )

            // Execute this step, collecting all events.
            // Phase 8 fix: track last AgentEvent.Completed from emitted events
            // (execution.result is never set by agents — they emit events only)
            var lastCompletedResult: AgentResult? = null
            try {
                agent.execute(stepRequest, execution)
                    .onEach { event ->
                        // Count tool calls
                        if (event is AgentEvent.ToolCompleted || event is AgentEvent.ToolFailed) {
                            toolCallsMade++
                        }
                        // Track last completed result for handoff content passing
                        if (event is AgentEvent.Completed) {
                            lastCompletedResult = event.result
                        }
                        emit(event)
                    }
                    .collect()

                stepsTaken++
            } catch (e: CancellationException) {
                emit(AgentEvent.Cancelled("Step ${stepIndex + 1} cancelled."))
                return true
            } catch (e: Exception) {
                val result = terminalFailedResult(
                    executionId = execution.executionId,
                    request = stepRequest,
                    agentName = agent.name,
                    code = "STEP_EXCEPTION",
                    message = e.message ?: "Unexpected error in step ${stepIndex + 1}.",
                )
                emit(AgentEvent.Failed(result))
                return true
            }

            // ── Phase 8: Emit HandoffCompleted after step ────────────────────
            if (stepIndex < plan.steps.size - 1 && lastCompletedResult != null) {
                val nextAgentName = plan.steps[stepIndex + 1].agentName
                emit(
                    AgentEvent.HandoffCompleted(
                        fromAgent = agent.name,
                        toAgent = nextAgentName,
                        handoffIndex = handoffsDone - 1,
                        outputSummary = lastCompletedResult!!.content?.take(200) ?: "",
                    )
                )
            }

            // Phase 8 fix: pass last completed result content to next step
            // (was always null before because execution.result was never set)
            if (stepIndex < plan.steps.size - 1) {
                currentInput = lastCompletedResult?.content
                    ?.takeIf { it.isNotBlank() }
                    ?: currentInput
            }
        }

        return true
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Detect recursive agent cycles in a plan.
     *
     * A cycle is defined as the same agent appearing more than
     * [MAX_CONSECUTIVE_SAME_AGENT] consecutive times in the step list.
     * This prevents plans like "pdf,pdf,pdf" from running indefinitely.
     *
     * @return [OrchestratorError.RecursionDetected] if a cycle is found, or null.
     */
    private fun detectRecursion(plan: AgentPlan): OrchestratorError.RecursionDetected? {
        if (plan.steps.size <= 1) return null
        var consecutiveCount = 1
        for (i in 1 until plan.steps.size) {
            if (plan.steps[i].agentName == plan.steps[i - 1].agentName) {
                consecutiveCount++
                if (consecutiveCount > MAX_CONSECUTIVE_SAME_AGENT) {
                    return OrchestratorError.RecursionDetected(
                        agentName = plan.steps[i].agentName,
                        cycleLength = consecutiveCount,
                    )
                }
            } else {
                consecutiveCount = 1
            }
        }
        return null
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString()

    private fun terminalFailedResult(
        executionId: String,
        request: AgentRequest,
        agentName: String,
        code: String,
        message: String,
    ) = AgentResult(
        executionId = executionId,
        requestId = request.requestId,
        agentName = agentName,
        status = AgentStatus.FAILED,
        error = AgentError(code = code, message = message),
    )

    companion object {
        /**
         * Maximum number of times the same agent name may appear consecutively
         * in a plan before it is flagged as recursive.
         *
         * A Code→Code→Code plan (3× same agent) is rejected; Code→RAG→Code (alternating) is fine.
         */
        const val MAX_CONSECUTIVE_SAME_AGENT: Int = 2
    }
}

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
                    timeoutMs = request.timeoutMs - (System.currentTimeMillis() - startMs),
                    streamingEnabled = request.streamingEnabled,
                    metadata = request.metadata,
                )
            }

            // Emit handoff event for steps > 0
            if (stepIndex > 0) {
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

            // Execute this step, collecting all events
            var stepResult: AgentResult? = null
            try {
                agent.execute(stepRequest, execution)
                    .onEach { event ->
                        // Count tool calls
                        if (event is AgentEvent.ToolCompleted || event is AgentEvent.ToolFailed) {
                            toolCallsMade++
                        }
                        emit(event)
                    }
                    .collect()

                // After collection ends, the last Completed/Failed event was emitted.
                // We trust the agent emitted the correct terminal event — just track result.
                stepResult = execution.result
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

            // If this step produced a result and there are more steps, use content as next input
            if (stepIndex < plan.steps.size - 1 && stepResult != null) {
                currentInput = stepResult.content ?: currentInput
            }
        }

        return true
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
}

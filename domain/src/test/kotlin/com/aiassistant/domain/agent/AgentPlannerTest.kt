/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentPlannerTest.kt
 * Purpose    : Unit tests for DefaultAgentPlanner — plan construction and limit checks.
 * ============================================================
 */
package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

class AgentPlannerTest {

    private val planner = DefaultAgentPlanner()

    private fun stubAgent(name: String) = object : Agent {
        override val name = name
        override val description = "stub"
        override val capabilities = emptySet<AgentCapability>()
        override fun execute(r: AgentRequest, e: AgentExecution): Flow<AgentEvent> = emptyFlow()
    }

    private fun registry(vararg names: String): DefaultAgentRegistry {
        val reg = DefaultAgentRegistry()
        names.forEach { reg.register(stubAgent(it)) }
        return reg
    }

    private fun req(
        metadata: Map<String, String> = emptyMap(),
        maxSteps: Int = AgentRequest.DEFAULT_MAX_STEPS,
        timeoutMs: Long = AgentRequest.DEFAULT_TIMEOUT_MS,
    ) = AgentRequest(
        userId = "u1",
        input = "test",
        maxSteps = maxSteps,
        timeoutMs = timeoutMs,
        metadata = metadata,
    )

    // ── Single-step plan ─────────────────────────────────────────────────────

    @Test
    fun `single-step plan built for simple request`() {
        val agent = stubAgent("conversational")
        val reg = registry("conversational")
        val plan = planner.buildPlan(req(), agent, reg)

        plan.steps shouldBe listOf(AgentPlanStep("conversational"))
        plan.isMultiStep.shouldBeFalse()
        plan.handoffCount shouldBe 0
        plan.maxHandoffs shouldBe 0
    }

    @Test
    fun `single-step plan inherits maxSteps and timeoutMs from request`() {
        val agent = stubAgent("alpha")
        val reg = registry("alpha")
        val plan = planner.buildPlan(req(maxSteps = 5, timeoutMs = 30_000L), agent, reg)

        plan.maxSteps shouldBe 5
        plan.timeoutMs shouldBe 30_000L
    }

    // ── Multi-step plan ──────────────────────────────────────────────────────

    @Test
    fun `multi-step plan from metadata plan_steps`() {
        val reg = registry("rag", "code")
        val agent = stubAgent("rag")
        val plan = planner.buildPlan(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "rag,code")),
            agent,
            reg,
        )

        plan.steps shouldBe listOf(AgentPlanStep("rag"), AgentPlanStep("code"))
        plan.isMultiStep.shouldBeTrue()
        plan.handoffCount shouldBe 1
    }

    @Test
    fun `multi-step plan fails when agent name not in registry`() {
        val reg = registry("rag")
        val agent = stubAgent("rag")
        shouldThrow<AgentNotFoundException> {
            planner.buildPlan(
                req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "rag,missing")),
                agent,
                reg,
            )
        }
    }

    @Test
    fun `three-step plan PDF to RAG to Code`() {
        val reg = registry("pdf", "rag", "code")
        val agent = stubAgent("pdf")
        val plan = planner.buildPlan(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "pdf,rag,code")),
            agent,
            reg,
        )

        plan.steps.map { it.agentName } shouldBe listOf("pdf", "rag", "code")
        plan.handoffCount shouldBe 2
        plan.isMultiStep.shouldBeTrue()
    }

    // ── AgentPlan validation ─────────────────────────────────────────────────

    @Test
    fun `plan with no steps throws`() {
        shouldThrow<IllegalArgumentException> {
            AgentPlan(steps = emptyList(), requestId = "r1")
        }
    }

    @Test
    fun `plan maxSteps 0 throws`() {
        shouldThrow<IllegalArgumentException> {
            AgentPlan(
                steps = listOf(AgentPlanStep("a")),
                requestId = "r1",
                maxSteps = 0,
            )
        }
    }

    @Test
    fun `plan with more steps than maxHandoffs allows throws`() {
        shouldThrow<IllegalArgumentException> {
            AgentPlan(
                steps = listOf(AgentPlanStep("a"), AgentPlanStep("b"), AgentPlanStep("c")),
                requestId = "r1",
                maxHandoffs = 1,  // only 1 allowed but 2 required
            )
        }
    }

    // ── checkLimits ──────────────────────────────────────────────────────────

    @Test
    fun `checkLimits returns null when all counters within budget`() {
        val plan = AgentPlan(
            steps = listOf(AgentPlanStep("a")),
            requestId = "r1",
            maxSteps = 10,
            maxHandoffs = 3,
            maxToolCalls = 20,
            timeoutMs = 60_000L,
        )
        val counters = PlanCounters(stepsTaken = 5, handoffsDone = 1, toolCallsMade = 5, elapsedMs = 1000L)
        planner.checkLimits(plan, counters).shouldBeNull()
    }

    @Test
    fun `checkLimits detects max steps exceeded`() {
        val plan = AgentPlan(listOf(AgentPlanStep("a")), "r1", maxSteps = 3)
        val counters = PlanCounters(stepsTaken = 3)
        val violation = planner.checkLimits(plan, counters).shouldNotBeNull()
        (violation is LimitViolation.MaxStepsExceeded).shouldBeTrue()
    }

    @Test
    fun `checkLimits detects max tool calls exceeded`() {
        val plan = AgentPlan(listOf(AgentPlanStep("a")), "r1", maxToolCalls = 5)
        val counters = PlanCounters(toolCallsMade = 5)
        val violation = planner.checkLimits(plan, counters).shouldNotBeNull()
        (violation is LimitViolation.MaxToolCallsExceeded).shouldBeTrue()
    }

    @Test
    fun `checkLimits detects timeout exceeded`() {
        val plan = AgentPlan(listOf(AgentPlanStep("a")), "r1", timeoutMs = 1000L)
        val counters = PlanCounters(elapsedMs = 1001L)
        val violation = planner.checkLimits(plan, counters).shouldNotBeNull()
        (violation is LimitViolation.TimeoutExceeded).shouldBeTrue()
    }
}

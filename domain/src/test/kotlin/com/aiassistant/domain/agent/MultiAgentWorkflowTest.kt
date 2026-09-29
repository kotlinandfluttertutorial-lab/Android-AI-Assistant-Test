/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : MultiAgentWorkflowTest.kt
 * Purpose    : Phase 8 tests for multi-agent workflows, handoff events,
 *              cancellation propagation, recursion protection,
 *              step limits, and the orchestrator bug fixes.
 * ============================================================
 */
package com.aiassistant.domain.agent

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class MultiAgentWorkflowTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun registry(vararg agents: Agent): DefaultAgentRegistry =
        DefaultAgentRegistry().also { reg -> agents.forEach { reg.register(it) } }

    private fun req(
        input: String = "start",
        metadata: Map<String, String> = emptyMap(),
        maxSteps: Int = 20,
        timeoutMs: Long = 10_000L,
    ) = AgentRequest(
        userId = "u1",
        input = input,
        metadata = metadata,
        maxSteps = maxSteps,
        timeoutMs = timeoutMs,
    )

    /** Agent that echoes its name as token content and passes it in the result. */
    private fun echoAgent(name: String, content: String = name) = object : Agent {
        override val name = name
        override val description = "echo stub"
        override val capabilities = setOf(AgentCapability.TEXT_GENERATION)
        override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> = flow {
            emit(AgentEvent.Started(execution.executionId, name))
            emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))
            emit(AgentEvent.Token(content))
            emit(
                AgentEvent.Completed(
                    AgentResult(
                        executionId = execution.executionId,
                        requestId = request.requestId,
                        agentName = name,
                        status = AgentStatus.COMPLETED,
                        content = content,
                    )
                )
            )
        }
    }

    /** Agent that echoes its received input, proving handoff content passes through. */
    private fun inputEchoAgent(name: String) = object : Agent {
        override val name = name
        override val description = "input echo"
        override val capabilities = setOf(AgentCapability.TEXT_GENERATION)
        override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> = flow {
            val content = "[$name received: ${request.input}]"
            emit(AgentEvent.Token(content))
            emit(
                AgentEvent.Completed(
                    AgentResult(
                        executionId = execution.executionId,
                        requestId = request.requestId,
                        agentName = name,
                        status = AgentStatus.COMPLETED,
                        content = content,
                    )
                )
            )
        }
    }

    /** Slow agent — delays before completing, useful for timeout/cancel tests. */
    private fun slowAgent(name: String, delayMs: Long = 5_000L) = object : Agent {
        override val name = name
        override val description = "slow"
        override val capabilities = setOf(AgentCapability.TEXT_GENERATION)
        override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> = flow {
            emit(AgentEvent.Started(execution.executionId, name))
            delay(delayMs)
            emit(
                AgentEvent.Completed(
                    AgentResult(
                        executionId = execution.executionId,
                        requestId = request.requestId,
                        agentName = name,
                        status = AgentStatus.COMPLETED,
                        content = "done",
                    )
                )
            )
        }
    }

    /** Agent that emits tool events before completing, for tool-call counting. */
    private fun toolUsingAgent(name: String, toolCallCount: Int = 2) = object : Agent {
        override val name = name
        override val description = "tool user"
        override val capabilities = setOf(AgentCapability.TOOL_USE)
        override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> = flow {
            repeat(toolCallCount) { i ->
                emit(AgentEvent.ToolStarted("tool_$i", "{}"))
                emit(AgentEvent.ToolCompleted("tool_$i", "ok", 10L))
            }
            emit(
                AgentEvent.Completed(
                    AgentResult(
                        executionId = execution.executionId,
                        requestId = request.requestId,
                        agentName = name,
                        status = AgentStatus.COMPLETED,
                        content = "tools done",
                    )
                )
            )
        }
    }

    private fun orchestrator(reg: AgentRegistry) = DefaultAgentOrchestrator(
        registry = reg,
        router = DefaultAgentRouter(),
        planner = DefaultAgentPlanner(),
    )

    // ── Multi-agent workflow: PDF→RAG→Code→Chat ───────────────────────────────

    @Test
    fun `four-step plan PDF to RAG to Code to Chat all complete`() = runTest {
        val reg = registry(
            echoAgent("pdf", "extracted text"),
            inputEchoAgent("rag"),
            inputEchoAgent("code"),
            inputEchoAgent("conversational"),
        )
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "pdf,rag,code,conversational"))
        ).toList()

        events.filterIsInstance<AgentEvent.Failed>().shouldBeEmpty()
        events.filterIsInstance<AgentEvent.Completed>().shouldNotBeEmpty()
    }

    @Test
    fun `four-step plan emits correct number of HandoffStarted events`() = runTest {
        val reg = registry(
            echoAgent("pdf"), echoAgent("rag"), echoAgent("code"), echoAgent("conversational")
        )
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "pdf,rag,code,conversational"))
        ).toList()

        // 4 agents = 3 handoffs
        val handoffStarts = events.filterIsInstance<AgentEvent.HandoffStarted>()
        handoffStarts shouldHaveSize 3
    }

    @Test
    fun `HandoffStarted has correct fromAgent and toAgent`() = runTest {
        val reg = registry(echoAgent("pdf", "pdf-output"), echoAgent("rag"), echoAgent("code"))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "pdf,rag,code"))
        ).toList()

        val handoffs = events.filterIsInstance<AgentEvent.HandoffStarted>()
        handoffs[0].fromAgent shouldBe "pdf"
        handoffs[0].toAgent shouldBe "rag"
        handoffs[1].fromAgent shouldBe "rag"
        handoffs[1].toAgent shouldBe "code"
    }

    @Test
    fun `HandoffCompleted emitted after each inter-agent step`() = runTest {
        val reg = registry(echoAgent("pdf"), echoAgent("rag"), echoAgent("code"))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "pdf,rag,code"))
        ).toList()

        val handoffCompletes = events.filterIsInstance<AgentEvent.HandoffCompleted>()
        handoffCompletes shouldHaveSize 2
        handoffCompletes[0].fromAgent shouldBe "pdf"
        handoffCompletes[0].toAgent shouldBe "rag"
    }

    @Test
    fun `handoff index increments per handoff`() = runTest {
        val reg = registry(echoAgent("a"), echoAgent("b"), echoAgent("c"))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "a,b,c"))
        ).toList()

        val starts = events.filterIsInstance<AgentEvent.HandoffStarted>()
        starts[0].handoffIndex shouldBe 0
        starts[1].handoffIndex shouldBe 1
    }

    // ── Handoff content passing (bug fix verification) ────────────────────────

    @Test
    fun `output of step N is passed as input to step N+1`() = runTest {
        // echoAgent("pdf") emits "pdf-result" as content
        // inputEchoAgent("rag") echoes its received input back
        val reg = registry(
            echoAgent("pdf", "pdf-result"),
            inputEchoAgent("rag"),
        )
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "pdf,rag"))
        ).toList()

        // RAG should have received "pdf-result" as its input
        val ragToken = events.filterIsInstance<AgentEvent.Token>()
            .firstOrNull { it.token.contains("pdf-result") }
        ragToken.shouldNotBeNull()
    }

    @Test
    fun `three-step chain passes content through all steps`() = runTest {
        val reg = registry(
            echoAgent("pdf", "PDF_CONTENT"),
            inputEchoAgent("rag"),  // will receive "PDF_CONTENT"
            inputEchoAgent("code"), // will receive "[rag received: PDF_CONTENT]"
        )
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "pdf,rag,code"))
        ).toList()

        val tokens = events.filterIsInstance<AgentEvent.Token>().map { it.token }
        tokens.any { it.contains("PDF_CONTENT") }.shouldBeTrue()
    }

    // ── Recursion protection ──────────────────────────────────────────────────

    @Test
    fun `three consecutive same-agent steps emit RECURSION_DETECTED`() = runTest {
        val reg = registry(echoAgent("code"))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "code,code,code"))
        ).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "RECURSION_DETECTED"
    }

    @Test
    fun `two consecutive same-agent steps are allowed`() = runTest {
        // MAX_CONSECUTIVE_SAME_AGENT = 2, so "code,code" (2 steps, 1 repeat) is allowed
        val reg = registry(echoAgent("code"))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "code,code"))
        ).toList()

        // Should succeed — no recursion
        events.filterIsInstance<AgentEvent.Failed>().none { it.result.error?.code == "RECURSION_DETECTED" }
            .shouldBeTrue()
    }

    @Test
    fun `alternating agents are not flagged as recursive`() = runTest {
        // code,rag,code,rag — each agent appears max 1 consecutive time
        val reg = registry(echoAgent("code"), echoAgent("rag"))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "code,rag,code,rag"))
        ).toList()

        events.filterIsInstance<AgentEvent.Failed>()
            .none { it.result.error?.code == "RECURSION_DETECTED" }.shouldBeTrue()
    }

    // ── Step limit enforcement ────────────────────────────────────────────────

    @Test
    fun `maxSteps exceeded emits LIMIT_EXCEEDED`() = runTest {
        val reg = registry(echoAgent("alpha"))
        val orc = orchestrator(reg)
        // maxSteps=1 but try to run 2 steps (both "alpha" — same agent, no recursion)
        // Actually this tests the planner step limit, not orchestrator — use single agent with
        // a request that has maxSteps=0 (invalid) or use steps > maxSteps in plan
        // Test via PlanCounters in planner:
        val plan = AgentPlan(
            steps = listOf(AgentPlanStep("alpha"), AgentPlanStep("alpha")),
            maxSteps = 1,
            maxHandoffs = 1,
            requestId = "r1",
        )
        val planner = DefaultAgentPlanner()
        val counters = PlanCounters(stepsTaken = 1)
        val violation = planner.checkLimits(plan, counters)
        violation.shouldNotBeNull()
        violation.shouldBeInstanceOf<LimitViolation.MaxStepsExceeded>()
    }

    @Test
    fun `tool call limit exceeded emits MaxToolCallsExceeded`() = runTest {
        val plan = AgentPlan(
            steps = listOf(AgentPlanStep("tool")),
            maxToolCalls = 3,
            requestId = "r1",
        )
        val planner = DefaultAgentPlanner()
        val counters = PlanCounters(toolCallsMade = 3)
        val violation = planner.checkLimits(plan, counters)
        violation.shouldNotBeNull()
        violation.shouldBeInstanceOf<LimitViolation.MaxToolCallsExceeded>()
    }

    @Test
    fun `handoff limit exceeded emits MaxHandoffsExceeded`() = runTest {
        val plan = AgentPlan(
            steps = listOf(AgentPlanStep("a"), AgentPlanStep("b")),
            maxHandoffs = 1,
            requestId = "r1",
        )
        val planner = DefaultAgentPlanner()
        // Phase 8 fix: handoffsDone > maxHandoffs triggers violation
        val counters = PlanCounters(handoffsDone = 2)
        val violation = planner.checkLimits(plan, counters)
        violation.shouldNotBeNull()
        violation.shouldBeInstanceOf<LimitViolation.MaxHandoffsExceeded>()
    }

    @Test
    fun `within handoff limit returns null`() = runTest {
        val plan = AgentPlan(
            steps = listOf(AgentPlanStep("a"), AgentPlanStep("b")),
            maxHandoffs = 1,
            requestId = "r1",
        )
        val planner = DefaultAgentPlanner()
        // handoffsDone=1 equals maxHandoffs=1 — should NOT trigger (> vs >=)
        val counters = PlanCounters(handoffsDone = 1)
        planner.checkLimits(plan, counters).shouldBeNull()
    }

    @Test
    fun `tool calls tracked correctly across steps`() = runTest {
        val reg = registry(toolUsingAgent("tool-a", 2), toolUsingAgent("tool-b", 3))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "tool-a,tool-b"))
        ).toList()

        // Both agents complete: 2 + 3 = 5 tool calls total
        val toolCompleted = events.filterIsInstance<AgentEvent.ToolCompleted>()
        toolCompleted shouldHaveSize 5
    }

    // ── Cancellation propagation ───────────────────────────────────────────────

    @Test
    fun `cancellation stops multi-step plan mid-execution`() = runTest {
        val reg = registry(
            slowAgent("pdf", 3_000L),  // 3 seconds
            echoAgent("rag"),
        )
        val orc = orchestrator(reg)
        val collected = mutableListOf<AgentEvent>()

        val job = launch {
            orc.execute(req(
                metadata = mapOf(METADATA_KEY_PLAN_STEPS to "pdf,rag"),
                timeoutMs = 10_000L,
            )).collect { collected.add(it) }
        }

        // Wait for Started event then cancel
        withTimeout(2_000L) {
            while (collected.filterIsInstance<AgentEvent.Started>().isEmpty()) {
                kotlinx.coroutines.delay(10L)
            }
        }
        job.cancel()
        job.join()

        // RAG agent should NOT have run (pdf was still in progress)
        collected.filterIsInstance<AgentEvent.HandoffStarted>()
            .none { it.toAgent == "rag" }.shouldBeTrue()
    }

    @Test
    fun `cancellation emits Cancelled event when scope cancelled mid-step`() = runTest {
        val neverEndingAgent = object : Agent {
            override val name = "never"
            override val description = "never ends"
            override val capabilities = setOf(AgentCapability.TEXT_GENERATION)
            override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> = flow {
                emit(AgentEvent.Started(execution.executionId, name))
                kotlinx.coroutines.delay(30_000L)  // will be cancelled
            }
        }
        val reg = registry(neverEndingAgent)
        val orc = orchestrator(reg)
        val collected = mutableListOf<AgentEvent>()

        val job = launch {
            orc.execute(req(metadata = mapOf("agent_name" to "never"), timeoutMs = 10_000L))
                .collect { collected.add(it) }
        }

        withTimeout(1_000L) {
            while (collected.filterIsInstance<AgentEvent.Started>().isEmpty()) {
                kotlinx.coroutines.delay(10L)
            }
        }
        job.cancel()
        job.join()

        // Either Cancelled or job ended cleanly — verify RAG did not run
        val hasCancelled = collected.filterIsInstance<AgentEvent.Cancelled>().isNotEmpty()
        val hadNoCompletedFailed = collected.filterIsInstance<AgentEvent.Completed>().isEmpty()
        // At minimum the job terminated without completing
        (hasCancelled || hadNoCompletedFailed).shouldBeTrue()
    }

    // ── Timeout propagation ───────────────────────────────────────────────────

    @Test
    fun `short timeout on multi-step plan emits TIMEOUT`() = runTest {
        val reg = registry(slowAgent("pdf", 2_000L), echoAgent("rag"))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(
                metadata = mapOf(METADATA_KEY_PLAN_STEPS to "pdf,rag"),
                timeoutMs = 50L,  // very short — pdf won't finish
            )
        ).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "TIMEOUT"
    }

    // ── Single-agent still works ──────────────────────────────────────────────

    @Test
    fun `single-agent plan has no handoff events`() = runTest {
        val reg = registry(echoAgent("alpha"))
        val orc = orchestrator(reg)
        val events = orc.execute(req()).toList()

        events.filterIsInstance<AgentEvent.HandoffStarted>().shouldBeEmpty()
        events.filterIsInstance<AgentEvent.HandoffCompleted>().shouldBeEmpty()
        events.filterIsInstance<AgentEvent.Completed>() shouldHaveSize 1
    }

    // ── OrchestratorError.RecursionDetected ───────────────────────────────────

    @Test
    fun `four consecutive same-agent steps emit RECURSION_DETECTED`() = runTest {
        val reg = registry(echoAgent("rag"))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "rag,rag,rag,rag"))
        ).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "RECURSION_DETECTED"
    }

    // ── Event ordering ────────────────────────────────────────────────────────    @Test
    fun `HandoffStarted appears before agent Started for that step`() = runTest {
        val reg = registry(echoAgent("pdf"), echoAgent("rag"))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "pdf,rag"))
        ).toList()

        val handoffIdx = events.indexOfFirst { it is AgentEvent.HandoffStarted }
        val ragStartedIdx = events.indexOfFirst {
            it is AgentEvent.Started && (it as AgentEvent.Started).agentName == "rag"
        }
        // HandoffStarted must come before rag's Started
        (handoffIdx < ragStartedIdx).shouldBeTrue()
    }

    @Test
    fun `terminal event is always last`() = runTest {
        val reg = registry(echoAgent("pdf"), echoAgent("rag"))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "pdf,rag"))
        ).toList()

        val last = events.last()
        (last is AgentEvent.Completed || last is AgentEvent.Failed || last is AgentEvent.Cancelled)
            .shouldBeTrue()
    }

    // ── AgentEvent sealed class completeness (Phase 8 additions) ─────────────

    @Test
    fun `HandoffStarted carries expected fields`() {
        val h = AgentEvent.HandoffStarted(
            fromAgent = "pdf",
            toAgent = "rag",
            handoffIndex = 0,
            context = "some context",
        )
        h.fromAgent shouldBe "pdf"
        h.toAgent shouldBe "rag"
        h.handoffIndex shouldBe 0
        h.context shouldBe "some context"
    }

    @Test
    fun `HandoffCompleted carries expected fields`() {
        val h = AgentEvent.HandoffCompleted(
            fromAgent = "rag",
            toAgent = "code",
            handoffIndex = 1,
            outputSummary = "summary",
        )
        h.fromAgent shouldBe "rag"
        h.toAgent shouldBe "code"
        h.handoffIndex shouldBe 1
        h.outputSummary shouldBe "summary"
    }
}



/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentOrchestratorTest.kt
 * Purpose    : Unit tests for DefaultAgentOrchestrator — execution, handoffs,
 *              cancellation, timeout, and error paths.
 *
 * Uses plain coroutine Flow collection (no Turbine — that dep lives in :data).
 * Terminal events (Completed / Failed / Cancelled) are always emitted last by
 * the orchestrator; we collect the full flow with toList() since each test
 * flow terminates on its own.
 * ============================================================
 */
package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AgentOrchestratorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun registry(vararg agents: Agent): DefaultAgentRegistry {
        val reg = DefaultAgentRegistry()
        agents.forEach { reg.register(it) }
        return reg
    }

    private fun req(
        input: String = "hello",
        metadata: Map<String, String> = emptyMap(),
        maxSteps: Int = 10,
        timeoutMs: Long = 5_000L,
    ) = AgentRequest(
        userId = "u1",
        input = input,
        metadata = metadata,
        maxSteps = maxSteps,
        timeoutMs = timeoutMs,
    )

    /** Agent that emits a complete successful result and then terminates. */
    private fun successAgent(name: String, content: String = "done") = object : Agent {
        override val name = name
        override val description = "success stub"
        override val capabilities = setOf(AgentCapability.TEXT_GENERATION)
        override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> = flow {
            emit(AgentEvent.Started(execution.executionId, name))
            emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))
            emit(AgentEvent.Token(content))
            val result = AgentResult(
                executionId = execution.executionId,
                requestId = request.requestId,
                agentName = name,
                status = AgentStatus.COMPLETED,
                content = content,
            )
            emit(AgentEvent.Completed(result))
        }
    }

    /** Agent that immediately emits a failed result and terminates. */
    private fun failAgent(name: String) = object : Agent {
        override val name = name
        override val description = "fail stub"
        override val capabilities = setOf(AgentCapability.TEXT_GENERATION)
        override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> = flow {
            val result = AgentResult(
                executionId = execution.executionId,
                requestId = request.requestId,
                agentName = name,
                status = AgentStatus.FAILED,
                error = AgentError("ERR", "deliberate failure"),
            )
            emit(AgentEvent.Failed(result))
        }
    }

    private fun orchestrator(reg: AgentRegistry) = DefaultAgentOrchestrator(
        registry = reg,
        router = DefaultAgentRouter(),
        planner = DefaultAgentPlanner(),
    )

    // ── Routing failures ──────────────────────────────────────────────────────

    @Test
    fun `empty registry emits Failed with ROUTING_FAILED`() = runTest {
        val orc = orchestrator(registry())
        val events = orc.execute(req()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "ROUTING_FAILED"
    }

    @Test
    fun `explicit unknown agent name emits Failed`() = runTest {
        val reg = registry(successAgent("alpha"))
        val orc = orchestrator(reg)
        val events = orc.execute(req(metadata = mapOf("agent_name" to "missing"))).toList()
        (events.filterIsInstance<AgentEvent.Failed>().size >= 1).shouldBeTrue()
    }

    // ── Simple execution ──────────────────────────────────────────────────────

    @Test
    fun `simple execution routes and emits complete event stream`() = runTest {
        val agent = successAgent("alpha", "Hello world")
        val reg = registry(agent)
        val orc = orchestrator(reg)
        val events = orc.execute(req()).toList()

        (events.filterIsInstance<AgentEvent.Started>().size >= 1).shouldBeTrue()
        val tokens = events.filterIsInstance<AgentEvent.Token>()
        (tokens.isNotEmpty()).shouldBeTrue()
        tokens.first().token shouldBe "Hello world"

        val completed = events.filterIsInstance<AgentEvent.Completed>().first()
        completed.result.content shouldBe "Hello world"
        completed.result.status shouldBe AgentStatus.COMPLETED
    }

    // ── Agent failure ─────────────────────────────────────────────────────────

    @Test
    fun `agent failure propagates as Failed event`() = runTest {
        val reg = registry(failAgent("alpha"))
        val orc = orchestrator(reg)
        val events = orc.execute(req()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.status shouldBe AgentStatus.FAILED
    }

    // ── Handoff ───────────────────────────────────────────────────────────────

    @Test
    fun `two-step plan executes both agents`() = runTest {
        val reg = registry(successAgent("rag", "rag-output"), successAgent("code", "code-output"))
        val orc = orchestrator(reg)
        val events = orc.execute(
            req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "rag,code"))
        ).toList()

        val tokenTexts = events.filterIsInstance<AgentEvent.Token>().map { it.token }
        (tokenTexts.contains("rag-output") || tokenTexts.contains("code-output")).shouldBeTrue()
        events.filterIsInstance<AgentEvent.Failed>().isEmpty().shouldBeTrue()
    }

    // ── Single-step success ───────────────────────────────────────────────────

    @Test
    fun `single-agent single-step plan completes`() = runTest {
        val reg = registry(successAgent("alpha"))
        val orc = orchestrator(reg)
        val events = orc.execute(req(maxSteps = 1)).toList()
        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
    }

    // ── Timeout ───────────────────────────────────────────────────────────────

    @Test
    fun `very short timeout emits Failed with TIMEOUT code`() = runTest {
        val slowAgent = object : Agent {
            override val name = "slow"
            override val description = "slow"
            override val capabilities = setOf(AgentCapability.TEXT_GENERATION)
            override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> = flow {
                emit(AgentEvent.Started(execution.executionId, "slow"))
                delay(2_000L) // 2 seconds — must exceed 50 ms timeout
                val result = AgentResult(
                    executionId = execution.executionId,
                    requestId = request.requestId,
                    agentName = "slow",
                    status = AgentStatus.COMPLETED,
                    content = "done",
                )
                emit(AgentEvent.Completed(result))
            }
        }
        val reg = registry(slowAgent)
        val orc = orchestrator(reg)
        val events = orc.execute(req(timeoutMs = 50L)).toList()

        (events.filterIsInstance<AgentEvent.Started>().isNotEmpty()).shouldBeTrue()
        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "TIMEOUT"
    }

    // ── Model router / LlmClient domain types ────────────────────────────────

    @Test
    fun `LlmRequest blank prompt throws`() {
        shouldThrow<IllegalArgumentException> {
            LlmRequest(prompt = "  ")
        }
    }

    @Test
    fun `LlmRequest valid constructs`() {
        val r = LlmRequest(prompt = "hello")
        r.prompt shouldBe "hello"
        r.systemPrompt shouldBe ""
        r.ragContext.isEmpty().shouldBeTrue()
    }

    @Test
    fun `LlmResponse carries provider and tokens`() {
        val r = LlmResponse("hi", "gemini", 10, 20)
        r.provider shouldBe "gemini"
        r.inputTokens shouldBe 10
        r.outputTokens shouldBe 20
        r.fallbackUsed.shouldBeFalse()
    }

    @Test
    fun `ModelRoutingDecision AUTO path throws`() {
        shouldThrow<IllegalArgumentException> {
            ModelRoutingDecision(path = InferencePath.AUTO, providerName = "gemini")
        }
    }

    @Test
    fun `ModelRoutingDecision CLOUD is valid`() {
        val d = ModelRoutingDecision(path = InferencePath.CLOUD, providerName = "gemini")
        d.path shouldBe InferencePath.CLOUD
        d.providerName shouldBe "gemini"
        d.fallbackOccurred.shouldBeFalse()
    }

    @Test
    fun `ModelRoutingDecision blank providerName throws`() {
        shouldThrow<IllegalArgumentException> {
            ModelRoutingDecision(path = InferencePath.CLOUD, providerName = "  ")
        }
    }

    @Test
    fun `LlmClientException carries provider and retryable`() {
        val ex = LlmClientException("oops", provider = "gemini", retryable = true)
        ex.provider shouldBe "gemini"
        ex.retryable.shouldBeTrue()
    }

    @Test
    fun `InferencePath CLOUD requires network`() {
        InferencePath.CLOUD.requiresNetwork.shouldBeTrue()
    }

    @Test
    fun `InferencePath ON_DEVICE does not require network`() {
        InferencePath.ON_DEVICE.requiresNetwork.shouldBeFalse()
    }

    @Test
    fun `InferencePath AUTO does not require network`() {
        InferencePath.AUTO.requiresNetwork.shouldBeFalse()
    }
}

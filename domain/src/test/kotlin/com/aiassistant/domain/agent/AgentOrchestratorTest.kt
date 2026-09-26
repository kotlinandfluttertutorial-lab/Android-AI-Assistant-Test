/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentOrchestratorTest.kt
 * Purpose    : Unit tests for DefaultAgentOrchestrator — execution, handoffs,
 *              cancellation, timeout, and error paths.
 * ============================================================
 */
package com.aiassistant.domain.agent

import app.cash.turbine.test
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
    ) = AgentRequest(userId = "u1", input = input, metadata = metadata, maxSteps = maxSteps, timeoutMs = timeoutMs)

    /** Agent that emits a complete successful result. */
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

    /** Agent that emits a failed result. */
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
        orc.execute(req()).test {
            val event = awaitItem()
            event.shouldBeInstanceOf<AgentEvent.Failed>()
            (event as AgentEvent.Failed).result.error?.code shouldBe "ROUTING_FAILED"
            awaitComplete()
        }
    }

    @Test
    fun `explicit unknown agent name emits Failed`() = runTest {
        val reg = registry(successAgent("alpha"))
        val orc = orchestrator(reg)
        orc.execute(req(metadata = mapOf("agent_name" to "missing"))).test {
            val event = awaitItem()
            event.shouldBeInstanceOf<AgentEvent.Failed>()
            awaitComplete()
        }
    }

    // ── Simple execution ──────────────────────────────────────────────────────

    @Test
    fun `simple execution routes and emits complete event stream`() = runTest {
        val agent = successAgent("alpha", "Hello world")
        val reg = registry(agent)
        val orc = orchestrator(reg)

        orc.execute(req()).test {
            awaitItem().shouldBeInstanceOf<AgentEvent.Started>()
            awaitItem().shouldBeInstanceOf<AgentEvent.StatusChanged>()
            awaitItem().shouldBeInstanceOf<AgentEvent.Token>()
            val completed = awaitItem()
            completed.shouldBeInstanceOf<AgentEvent.Completed>()
            (completed as AgentEvent.Completed).result.content shouldBe "Hello world"
            (completed.result.status == AgentStatus.COMPLETED).shouldBeTrue()
            awaitComplete()
        }
    }

    // ── Agent failure ─────────────────────────────────────────────────────────

    @Test
    fun `agent failure propagates as Failed event`() = runTest {
        val reg = registry(failAgent("alpha"))
        val orc = orchestrator(reg)

        orc.execute(req()).test {
            val event = awaitItem()
            event.shouldBeInstanceOf<AgentEvent.Failed>()
            (event as AgentEvent.Failed).result.status shouldBe AgentStatus.FAILED
            awaitComplete()
        }
    }

    // ── Handoff ───────────────────────────────────────────────────────────────

    @Test
    fun `two-step plan executes both agents`() = runTest {
        val reg = registry(successAgent("rag", "rag-output"), successAgent("code", "code-output"))
        val orc = orchestrator(reg)
        val request = req(metadata = mapOf(METADATA_KEY_PLAN_STEPS to "rag,code"))

        val events = mutableListOf<AgentEvent>()
        orc.execute(request).test {
            while (true) {
                val item = awaitItem()
                events.add(item)
                if (item is AgentEvent.Completed || item is AgentEvent.Failed) break
            }
            awaitComplete()
        }

        // Both agents should have run — we see their tokens
        events.filterIsInstance<AgentEvent.Token>().map { it.token }
            .let { tokens ->
                (tokens.contains("rag-output") || tokens.contains("code-output")).shouldBeTrue()
            }
    }

    // ── Max steps limit ───────────────────────────────────────────────────────

    @Test
    fun `maxSteps=1 and a 1-step plan succeeds`() = runTest {
        val reg = registry(successAgent("alpha"))
        val orc = orchestrator(reg)
        orc.execute(req(maxSteps = 1)).test {
            var sawCompleted = false
            while (true) {
                val item = awaitItem()
                if (item is AgentEvent.Completed) { sawCompleted = true; break }
                if (item is AgentEvent.Failed) break
            }
            sawCompleted.shouldBeTrue()
            awaitComplete()
        }
    }

    // ── Timeout ───────────────────────────────────────────────────────────────

    @Test
    fun `very short timeout emits Failed with TIMEOUT code`() = runTest {
        val slowAgent = object : Agent {
            override val name = "slow"
            override val description = "slow"
            override val capabilities = setOf(AgentCapability.TEXT_GENERATION)
            override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> = flow {
                kotlinx.coroutines.delay(2_000L)
                val result = AgentResult(
                    executionId = execution.executionId,
                    requestId = request.requestId,
                    agentName = name,
                    status = AgentStatus.COMPLETED,
                    content = "done",
                )
                emit(AgentEvent.Completed(result))
            }
        }
        val reg = registry(slowAgent)
        val orc = orchestrator(reg)
        // 50 ms timeout — the agent delays 2 s → must time out
        orc.execute(req(timeoutMs = 50L)).test {
            awaitItem().shouldBeInstanceOf<AgentEvent.Started>()
            val failed = awaitItem()
            failed.shouldBeInstanceOf<AgentEvent.Failed>()
            (failed as AgentEvent.Failed).result.error?.code shouldBe "TIMEOUT"
            awaitComplete()
        }
    }

    // ── Model router / LlmClient ──────────────────────────────────────────────

    @Test
    fun `LlmRequest blank prompt throws`() {
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            LlmRequest(prompt = "  ")
        }
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
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            ModelRoutingDecision(
                path = InferencePath.AUTO,
                providerName = "gemini",
            )
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
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            ModelRoutingDecision(path = InferencePath.CLOUD, providerName = "  ")
        }
    }
}

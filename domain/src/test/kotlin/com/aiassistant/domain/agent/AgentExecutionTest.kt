/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentExecutionTest.kt
 * Purpose    : Unit tests for AgentExecution immutable state transitions.
 *
 * Architecture Layer : Domain — agent sub-package (test)
 * Pattern Used       : JUnit4 + Kotest assertions
 * ============================================================
 */

package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class AgentExecutionTest {

    private fun makeRequest() = AgentRequest(userId = "u1", input = "Do something")

    private fun makeExecution(agentName: String = "test-agent") = AgentExecution(
        request = makeRequest(),
        agentName = agentName,
    )

    // ── Construction ────────────────────────────────────────────────────────

    @Test
    fun `new execution starts in REQUESTED status`() {
        val exec = makeExecution()
        exec.status shouldBe AgentStatus.REQUESTED
        exec.steps shouldBe emptyList()
        exec.result shouldBe null
        exec.isTerminal.shouldBeFalse()
        exec.isSuccess.shouldBeFalse()
    }

    @Test
    fun `blank agentName throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentExecution(request = makeRequest(), agentName = "  ")
        }
    }

    // ── withStatus ──────────────────────────────────────────────────────────

    @Test
    fun `valid transition REQUESTED to STARTED succeeds`() {
        val exec = makeExecution().withStatus(AgentStatus.STARTED)
        exec.status shouldBe AgentStatus.STARTED
    }

    @Test
    fun `valid chain REQUESTED to STARTED to RUNNING succeeds`() {
        val exec = makeExecution()
            .withStatus(AgentStatus.STARTED)
            .withStatus(AgentStatus.RUNNING)
        exec.status shouldBe AgentStatus.RUNNING
    }

    @Test
    fun `invalid transition REQUESTED to RUNNING throws IllegalStateException`() {
        shouldThrow<IllegalStateException> {
            makeExecution().withStatus(AgentStatus.RUNNING)
        }
    }

    @Test
    fun `transition to terminal status sets completedAt`() {
        val exec = makeExecution()
            .withStatus(AgentStatus.STARTED)
            .withStatus(AgentStatus.RUNNING)
            .withStatus(AgentStatus.COMPLETED)

        exec.completedAt.shouldNotBeNull()
        exec.isTerminal.shouldBeTrue()
    }

    @Test
    fun `non-terminal transition does not set completedAt`() {
        val exec = makeExecution().withStatus(AgentStatus.STARTED)
        exec.completedAt.shouldBeNull()
    }

    @Test
    fun `transition from terminal status throws IllegalStateException`() {
        val terminal = makeExecution()
            .withStatus(AgentStatus.STARTED)
            .withStatus(AgentStatus.FAILED)

        shouldThrow<IllegalStateException> {
            terminal.withStatus(AgentStatus.RUNNING)
        }
    }

    // ── withStep ────────────────────────────────────────────────────────────

    @Test
    fun `withStep appends to steps list`() {
        val step = AgentStep(
            stepIndex = 0,
            decision = AgentDecision.Respond(content = "hi", isFinal = true),
        )
        val exec = makeExecution()
            .withStatus(AgentStatus.STARTED)
            .withStatus(AgentStatus.RUNNING)
            .withStep(step)

        exec.steps shouldBe listOf(step)
        exec.stepCount shouldBe 1
    }

    @Test
    fun `multiple steps accumulate in order`() {
        val step0 = AgentStep(stepIndex = 0, decision = AgentDecision.Finish())
        val step1 = AgentStep(stepIndex = 1, decision = AgentDecision.Finish())
        val exec = makeExecution()
            .withStatus(AgentStatus.STARTED)
            .withStatus(AgentStatus.RUNNING)
            .withStep(step0)
            .withStep(step1)

        exec.steps[0].stepIndex shouldBe 0
        exec.steps[1].stepIndex shouldBe 1
        exec.stepCount shouldBe 2
    }

    // ── withResult ──────────────────────────────────────────────────────────

    @Test
    fun `withResult sets result and transitions status`() {
        val result = AgentResult(
            executionId = "e1",
            requestId = makeRequest().requestId,
            agentName = "test-agent",
            status = AgentStatus.COMPLETED,
            content = "Answer",
        )
        val exec = makeExecution()
            .withStatus(AgentStatus.STARTED)
            .withStatus(AgentStatus.RUNNING)
            .withResult(result)

        exec.status shouldBe AgentStatus.COMPLETED
        exec.result.shouldNotBeNull()
        exec.result!!.content shouldBe "Answer"
        exec.isTerminal.shouldBeTrue()
        exec.isSuccess.shouldBeTrue()
    }

    // ── cancel ──────────────────────────────────────────────────────────────

    @Test
    fun `cancel from RUNNING moves to CANCELLED`() {
        val exec = makeExecution()
            .withStatus(AgentStatus.STARTED)
            .withStatus(AgentStatus.RUNNING)
            .cancel()

        exec.status shouldBe AgentStatus.CANCELLED
        exec.isTerminal.shouldBeTrue()
    }

    @Test
    fun `cancel from REQUESTED moves to CANCELLED`() {
        val exec = makeExecution().cancel()
        exec.status shouldBe AgentStatus.CANCELLED
    }

    @Test
    fun `cancel from already terminal state returns same execution`() {
        val completed = makeExecution()
            .withStatus(AgentStatus.STARTED)
            .withStatus(AgentStatus.RUNNING)
            .withStatus(AgentStatus.COMPLETED)

        val after = completed.cancel()
        // No change: still COMPLETED
        after.status shouldBe AgentStatus.COMPLETED
    }

    // ── totalTokens ─────────────────────────────────────────────────────────

    @Test
    fun `totalTokens is 0 when result is null`() {
        makeExecution().totalTokens shouldBe 0
    }

    @Test
    fun `totalTokens reflects result usage`() {
        val result = AgentResult(
            executionId = "e",
            requestId = makeRequest().requestId,
            agentName = "test-agent",
            status = AgentStatus.COMPLETED,
            usage = AgentUsage(inputTokens = 50, outputTokens = 100),
        )
        val exec = makeExecution()
            .withStatus(AgentStatus.STARTED)
            .withStatus(AgentStatus.RUNNING)
            .withResult(result)

        exec.totalTokens shouldBe 150
    }

    // ── Immutability ─────────────────────────────────────────────────────────

    @Test
    fun `original execution is unchanged after withStatus`() {
        val original = makeExecution()
        original.withStatus(AgentStatus.STARTED)

        original.status shouldBe AgentStatus.REQUESTED
    }
}

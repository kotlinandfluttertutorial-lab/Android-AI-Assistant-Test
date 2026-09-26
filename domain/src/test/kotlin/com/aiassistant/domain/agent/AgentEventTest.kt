/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentEventTest.kt
 * Purpose    : Unit tests for AgentEvent sealed variants.
 *
 * Architecture Layer : Domain — agent sub-package (test)
 * Pattern Used       : JUnit4 + Kotest assertions
 * ============================================================
 */

package com.aiassistant.domain.agent

import io.kotest.matchers.shouldBe
import org.junit.Test

class AgentEventTest {

    private val doneResult = AgentResult(
        executionId = "exec-1",
        requestId = "req-1",
        agentName = "test",
        status = AgentStatus.COMPLETED,
        content = "done",
    )
    private val failedResult = AgentResult(
        executionId = "exec-1",
        requestId = "req-1",
        agentName = "test",
        status = AgentStatus.FAILED,
        error = AgentError("ERR", "Something went wrong"),
    )

    @Test
    fun `Started carries executionId and agentName`() {
        val e = AgentEvent.Started(executionId = "exec-1", agentName = "conversational")
        e.executionId shouldBe "exec-1"
        e.agentName shouldBe "conversational"
    }

    @Test
    fun `StatusChanged carries executionId and status`() {
        val e = AgentEvent.StatusChanged(executionId = "exec-1", status = AgentStatus.RUNNING)
        e.executionId shouldBe "exec-1"
        e.status shouldBe AgentStatus.RUNNING
    }

    @Test
    fun `Token carries text fragment`() {
        val e = AgentEvent.Token(token = "Hello")
        e.token shouldBe "Hello"
    }

    @Test
    fun `Thinking carries stepIndex and thought`() {
        val e = AgentEvent.Thinking(stepIndex = 0, thought = "I need to search docs first.")
        e.stepIndex shouldBe 0
        e.thought shouldBe "I need to search docs first."
    }

    @Test
    fun `ToolStarted carries toolName and parameters`() {
        val e = AgentEvent.ToolStarted(toolName = "github", parameters = "{}")
        e.toolName shouldBe "github"
        e.parameters shouldBe "{}"
    }

    @Test
    fun `ToolCompleted carries toolName, output, and durationMs`() {
        val e = AgentEvent.ToolCompleted(toolName = "github", output = """{"id":1}""", durationMs = 123L)
        e.toolName shouldBe "github"
        e.output shouldBe """{"id":1}"""
        e.durationMs shouldBe 123L
    }

    @Test
    fun `ToolFailed carries toolName and errorMessage`() {
        val e = AgentEvent.ToolFailed(toolName = "slack", errorMessage = "Rate limit exceeded")
        e.toolName shouldBe "slack"
        e.errorMessage shouldBe "Rate limit exceeded"
    }

    @Test
    fun `ToolConfirmationRequired carries toolName, parameters, and rationale`() {
        val e = AgentEvent.ToolConfirmationRequired(
            toolName = "gmail",
            parameters = "{}",
            rationale = "Sending email",
        )
        e.toolName shouldBe "gmail"
        e.rationale shouldBe "Sending email"
    }

    @Test
    fun `RetrievalCompleted carries query and chunkCount`() {
        val e = AgentEvent.RetrievalCompleted(query = "What is AI?", chunkCount = 5)
        e.query shouldBe "What is AI?"
        e.chunkCount shouldBe 5
    }

    @Test
    fun `Completed wraps AgentResult`() {
        val e = AgentEvent.Completed(result = doneResult)
        e.result.status shouldBe AgentStatus.COMPLETED
        e.result.content shouldBe "done"
    }

    @Test
    fun `Failed wraps AgentResult with FAILED status`() {
        val e = AgentEvent.Failed(result = failedResult)
        e.result.status shouldBe AgentStatus.FAILED
        e.result.error?.code shouldBe "ERR"
    }

    @Test
    fun `Cancelled has default reason`() {
        val e = AgentEvent.Cancelled()
        e.reason shouldBe "Cancelled by user."
    }

    @Test
    fun `Cancelled with custom reason`() {
        val e = AgentEvent.Cancelled(reason = "Low memory")
        e.reason shouldBe "Low memory"
    }

    @Test
    fun `sealed exhaustive when expression over all terminal events`() {
        val events: List<AgentEvent> = listOf(
            AgentEvent.Started("e", "a"),
            AgentEvent.StatusChanged("e", AgentStatus.RUNNING),
            AgentEvent.Token("tok"),
            AgentEvent.Thinking(0, "..."),
            AgentEvent.ToolStarted("t", "{}"),
            AgentEvent.ToolCompleted("t", "{}", 10L),
            AgentEvent.ToolFailed("t", "err"),
            AgentEvent.ToolConfirmationRequired("t", "{}"),
            AgentEvent.RetrievalCompleted("q", 3),
            AgentEvent.Completed(doneResult),
            AgentEvent.Failed(failedResult),
            AgentEvent.Cancelled(),
        )
        val types = events.map { event ->
            when (event) {
                is AgentEvent.Started                 -> "started"
                is AgentEvent.StatusChanged           -> "status_changed"
                is AgentEvent.Token                   -> "token"
                is AgentEvent.Thinking                -> "thinking"
                is AgentEvent.ToolStarted             -> "tool_started"
                is AgentEvent.ToolCompleted           -> "tool_completed"
                is AgentEvent.ToolFailed              -> "tool_failed"
                is AgentEvent.ToolConfirmationRequired -> "tool_confirmation_required"
                is AgentEvent.RetrievalCompleted      -> "retrieval_completed"
                is AgentEvent.Completed               -> "completed"
                is AgentEvent.Failed                  -> "failed"
                is AgentEvent.Cancelled               -> "cancelled"
            }
        }
        types shouldBe listOf(
            "started", "status_changed", "token", "thinking",
            "tool_started", "tool_completed", "tool_failed", "tool_confirmation_required",
            "retrieval_completed", "completed", "failed", "cancelled",
        )
    }
}

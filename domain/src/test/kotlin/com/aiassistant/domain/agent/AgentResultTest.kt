/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentResultTest.kt
 * Purpose    : Unit tests for AgentResult construction, validation, and helpers.
 *
 * Architecture Layer : Domain — agent sub-package (test)
 * Pattern Used       : JUnit4 + Kotest assertions
 * ============================================================
 */

package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.Test

class AgentResultTest {

    private fun minimalResult(
        status: AgentStatus = AgentStatus.COMPLETED,
        content: String? = "Done",
    ) = AgentResult(
        executionId = "exec-1",
        requestId = "req-1",
        agentName = "conversational",
        status = status,
        content = content,
    )

    // ── Construction ────────────────────────────────────────────────────────

    @Test
    fun `minimal COMPLETED result constructs successfully`() {
        val result = minimalResult()
        result.status shouldBe AgentStatus.COMPLETED
        result.content shouldBe "Done"
        result.toolCalls shouldBe emptyList()
        result.citations shouldBe emptyList()
        result.attachments shouldBe emptyList()
        result.usage shouldBe null
        result.error shouldBe null
        result.nextAction shouldBe null
    }

    @Test
    fun `FAILED result can carry AgentError`() {
        val result = AgentResult(
            executionId = "exec-2",
            requestId = "req-2",
            agentName = "code-analysis",
            status = AgentStatus.FAILED,
            error = AgentError(code = "LLM_TIMEOUT", message = "The LLM timed out."),
        )
        result.status shouldBe AgentStatus.FAILED
        result.error?.code shouldBe "LLM_TIMEOUT"
    }

    @Test
    fun `PARTIAL result is valid terminal status`() {
        val result = minimalResult(status = AgentStatus.PARTIAL, content = "Partial answer")
        result.status shouldBe AgentStatus.PARTIAL
    }

    @Test
    fun `CANCELLED result constructs without content`() {
        val result = minimalResult(status = AgentStatus.CANCELLED, content = null)
        result.status shouldBe AgentStatus.CANCELLED
        result.content shouldBe null
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    fun `non-terminal status throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentResult(
                executionId = "e",
                requestId = "r",
                agentName = "a",
                status = AgentStatus.RUNNING,  // not terminal
            )
        }
    }

    @Test
    fun `blank executionId throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentResult(
                executionId = "  ",
                requestId = "r",
                agentName = "a",
                status = AgentStatus.COMPLETED,
            )
        }
    }

    @Test
    fun `blank requestId throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentResult(
                executionId = "e",
                requestId = "",
                agentName = "a",
                status = AgentStatus.COMPLETED,
            )
        }
    }

    @Test
    fun `blank agentName throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            AgentResult(
                executionId = "e",
                requestId = "r",
                agentName = " ",
                status = AgentStatus.COMPLETED,
            )
        }
    }

    // ── Computed properties ─────────────────────────────────────────────────

    @Test
    fun `hasContent is true when content is non-blank`() =
        minimalResult(content = "something").hasContent.shouldBeTrue()

    @Test
    fun `hasContent is false when content is null`() =
        minimalResult(content = null).hasContent.shouldBeFalse()

    @Test
    fun `hasContent is false when content is blank`() =
        minimalResult(content = "   ").hasContent.shouldBeFalse()

    @Test
    fun `hasCitations is false when citations is empty`() =
        minimalResult().hasCitations.shouldBeFalse()

    @Test
    fun `hasCitations is true when citations exist`() {
        val result = AgentResult(
            executionId = "e",
            requestId = "r",
            agentName = "rag",
            status = AgentStatus.COMPLETED,
            citations = listOf(AgentCitation("d1", "Doc A", "excerpt", score = 0.9f)),
        )
        result.hasCitations.shouldBeTrue()
    }

    @Test
    fun `hasToolErrors is false when all tool calls succeeded`() {
        val result = AgentResult(
            executionId = "e",
            requestId = "r",
            agentName = "tool-agent",
            status = AgentStatus.COMPLETED,
            toolCalls = listOf(
                AgentToolCall(toolName = "github", input = "{}", output = "ok"),
            ),
        )
        result.hasToolErrors.shouldBeFalse()
    }

    @Test
    fun `hasToolErrors is true when any tool call failed`() {
        val result = AgentResult(
            executionId = "e",
            requestId = "r",
            agentName = "tool-agent",
            status = AgentStatus.PARTIAL,
            toolCalls = listOf(
                AgentToolCall(toolName = "github", input = "{}", failed = true, errorMessage = "timeout"),
            ),
        )
        result.hasToolErrors.shouldBeTrue()
    }

    // ── AgentUsage ──────────────────────────────────────────────────────────

    @Test
    fun `AgentUsage totalTokens defaults to sum`() {
        val usage = AgentUsage(inputTokens = 100, outputTokens = 250)
        usage.totalTokens shouldBe 350
    }

    @Test
    fun `AgentUsage totalTokens can be overridden`() {
        val usage = AgentUsage(inputTokens = 100, outputTokens = 250, totalTokens = 400)
        usage.totalTokens shouldBe 400
    }
}

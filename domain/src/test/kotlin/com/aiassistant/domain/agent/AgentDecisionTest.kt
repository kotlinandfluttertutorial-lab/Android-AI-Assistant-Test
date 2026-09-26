/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentDecisionTest.kt
 * Purpose    : Unit tests for AgentDecision sealed variants.
 *
 * Architecture Layer : Domain — agent sub-package (test)
 * Pattern Used       : JUnit4 + Kotest assertions
 * ============================================================
 */

package com.aiassistant.domain.agent

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class AgentDecisionTest {

    @Test
    fun `Respond stores content and defaults`() {
        val d = AgentDecision.Respond(content = "Hello")
        d.content shouldBe "Hello"
        d.isFinal.shouldBeTrue()
        d.streaming.shouldBeFalse()
    }

    @Test
    fun `Respond non-final and streaming can be set`() {
        val d = AgentDecision.Respond(content = "...", isFinal = false, streaming = true)
        d.isFinal.shouldBeFalse()
        d.streaming.shouldBeTrue()
    }

    @Test
    fun `CallTool stores tool name and parameters`() {
        val d = AgentDecision.CallTool(toolName = "github", parameters = """{"title":"Bug"}""")
        d.toolName shouldBe "github"
        d.parameters shouldBe """{"title":"Bug"}"""
        d.requiresConfirmation.shouldBeFalse()
        d.rationale.shouldBeNull()
    }

    @Test
    fun `CallTool with confirmation and rationale`() {
        val d = AgentDecision.CallTool(
            toolName = "gmail",
            parameters = "{}",
            requiresConfirmation = true,
            rationale = "Sending an email on your behalf",
        )
        d.requiresConfirmation.shouldBeTrue()
        d.rationale shouldBe "Sending an email on your behalf"
    }

    @Test
    fun `Retrieve defaults topK and minScore`() {
        val d = AgentDecision.Retrieve(query = "What is RAG?")
        d.query shouldBe "What is RAG?"
        d.documentIds shouldBe emptyList()
        d.topK shouldBe 5
        d.minScore shouldBe 0.4f
    }

    @Test
    fun `Retrieve with custom parameters`() {
        val d = AgentDecision.Retrieve(
            query = "architecture",
            documentIds = listOf("doc-1"),
            topK = 3,
            minScore = 0.6f,
        )
        d.documentIds shouldBe listOf("doc-1")
        d.topK shouldBe 3
        d.minScore shouldBe 0.6f
    }

    @Test
    fun `Wait defaults waitForType`() {
        val d = AgentDecision.Wait(reason = "Awaiting confirmation")
        d.reason shouldBe "Awaiting confirmation"
        d.waitForType shouldBe "user_confirmation"
        d.payload.shouldBeNull()
    }

    @Test
    fun `Finish has default reason`() {
        val d = AgentDecision.Finish()
        d.reason shouldBe "Task completed."
    }

    @Test
    fun `Finish with custom reason`() {
        val d = AgentDecision.Finish(reason = "Context limit reached")
        d.reason shouldBe "Context limit reached"
    }

    @Test
    fun `sealed exhaustive when expression compiles`() {
        // This test verifies that a when expression over AgentDecision is
        // exhaustive — if a new variant is added without handling it here,
        // the compiler will report a warning (or error with -Werror).
        val decisions: List<AgentDecision> = listOf(
            AgentDecision.Respond("hi"),
            AgentDecision.CallTool("github", "{}"),
            AgentDecision.Retrieve("query"),
            AgentDecision.Wait("reason"),
            AgentDecision.Finish(),
        )
        val labels = decisions.map { decision ->
            when (decision) {
                is AgentDecision.Respond  -> "respond"
                is AgentDecision.CallTool -> "call_tool"
                is AgentDecision.Retrieve -> "retrieve"
                is AgentDecision.Wait     -> "wait"
                is AgentDecision.Finish   -> "finish"
            }
        }
        labels shouldBe listOf("respond", "call_tool", "retrieve", "wait", "finish")
    }
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentContextTest.kt
 * Purpose    : Unit tests for AgentContext construction and helpers.
 *
 * Architecture Layer : Domain — agent sub-package (test)
 * Pattern Used       : JUnit4 + Kotest assertions
 * ============================================================
 */

package com.aiassistant.domain.agent

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test

class AgentContextTest {

    @Test
    fun `minimal context constructs with defaults`() {
        val ctx = AgentContext(userId = "u1")

        ctx.userId shouldBe "u1"
        ctx.conversationId shouldBe null
        ctx.conversationHistory shouldBe emptyList()
        ctx.memories shouldBe emptyList()
        ctx.personaSystemPrompt shouldBe null
        ctx.ragDocumentIds shouldBe emptyList()
        ctx.availableTools shouldBe emptyList()
        ctx.isPrivacyMode shouldBe false
        ctx.isOffline shouldBe false
        ctx.extraContext shouldBe emptyMap()
    }

    @Test
    fun `withMemories appends to existing memories`() {
        val initial = AgentContext(
            userId = "u1",
            memories = listOf(ContextMemory("first", 0.9f)),
        )
        val additional = listOf(ContextMemory("second", 0.8f))
        val updated = initial.withMemories(additional)

        updated.memories shouldHaveSize 2
        updated.memories shouldContain ContextMemory("first", 0.9f)
        updated.memories shouldContain ContextMemory("second", 0.8f)
    }

    @Test
    fun `withMemories on empty context produces single-item list`() {
        val ctx = AgentContext(userId = "u1")
        val updated = ctx.withMemories(listOf(ContextMemory("fact", 0.7f)))

        updated.memories shouldHaveSize 1
        updated.memories[0].content shouldBe "fact"
    }

    @Test
    fun `withTools adds tools and deduplicates`() {
        val ctx = AgentContext(userId = "u1", availableTools = listOf("github"))
        val updated = ctx.withTools(listOf("github", "gmail"))

        updated.availableTools shouldHaveSize 2
        updated.availableTools shouldContain "github"
        updated.availableTools shouldContain "gmail"
    }

    @Test
    fun `withTools on empty context produces tool list`() {
        val ctx = AgentContext(userId = "u1")
        val updated = ctx.withTools(listOf("slack"))

        updated.availableTools shouldHaveSize 1
        updated.availableTools[0] shouldBe "slack"
    }

    @Test
    fun `original context is not mutated by withMemories`() {
        val original = AgentContext(userId = "u1")
        original.withMemories(listOf(ContextMemory("x", 1f)))

        original.memories shouldBe emptyList()
    }

    @Test
    fun `ContextMessage stores role and content`() {
        val msg = ContextMessage(role = "user", content = "Hello")
        msg.role shouldBe "user"
        msg.content shouldBe "Hello"
    }

    @Test
    fun `ContextMemory stores content and relevance score`() {
        val mem = ContextMemory(content = "User prefers dark mode", relevanceScore = 0.95f)
        mem.content shouldBe "User prefers dark mode"
        mem.relevanceScore shouldBe 0.95f
    }
}

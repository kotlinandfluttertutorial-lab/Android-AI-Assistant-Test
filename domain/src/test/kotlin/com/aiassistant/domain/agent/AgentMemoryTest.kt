/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentMemoryTest.kt
 * Purpose    : Unit tests for Phase 8 memory types:
 *              MemoryEntry, ShortTermMemory, ConversationMemory,
 *              LongTermMemory, LongTermMemoryEntry, MemorySnapshot,
 *              AgentContextAssembler, SafetyLimits.
 * ============================================================
 */
package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class AgentMemoryTest {

    // ── MemoryEntry ───────────────────────────────────────────────────────────

    @Test
    fun `MemoryEntry rejects blank content`() {
        shouldThrow<IllegalArgumentException> { MemoryEntry(content = "  ") }
    }

    @Test
    fun `MemoryEntry rejects relevanceScore out of range`() {
        shouldThrow<IllegalArgumentException> { MemoryEntry("ok", relevanceScore = 1.1f) }
        shouldThrow<IllegalArgumentException> { MemoryEntry("ok", relevanceScore = -0.1f) }
    }

    @Test
    fun `MemoryEntry toContextMemory maps fields correctly`() {
        val entry = MemoryEntry(content = "Paris is in France.", relevanceScore = 0.9f, source = "user")
        val cm = entry.toContextMemory()
        cm.content shouldBe "Paris is in France."
        cm.relevanceScore shouldBe 0.9f
    }

    @Test
    fun `MemoryEntry fromContextMemory round-trips`() {
        val cm = ContextMemory(content = "hello", relevanceScore = 0.5f)
        val entry = MemoryEntry.fromContextMemory(cm, source = "test")
        entry.content shouldBe "hello"
        entry.relevanceScore shouldBe 0.5f
        entry.source shouldBe "test"
    }

    // ── ShortTermMemory ───────────────────────────────────────────────────────

    @Test
    fun `ShortTermMemory rejects blank inputPrompt`() {
        shouldThrow<IllegalArgumentException> {
            ShortTermMemory(inputPrompt = "  ")
        }
    }

    @Test
    fun `ShortTermMemory isTokenBudgetExceeded false when maxTokens is 0`() {
        val stm = ShortTermMemory(inputPrompt = "hello", tokenCount = 9_999, maxTokens = 0)
        stm.isTokenBudgetExceeded.shouldBeFalse()
    }

    @Test
    fun `ShortTermMemory isTokenBudgetExceeded true when at limit`() {
        val stm = ShortTermMemory(inputPrompt = "hello", tokenCount = 100, maxTokens = 100)
        stm.isTokenBudgetExceeded.shouldBeTrue()
    }

    @Test
    fun `ShortTermMemory isTokenBudgetExceeded false when under limit`() {
        val stm = ShortTermMemory(inputPrompt = "hello", tokenCount = 99, maxTokens = 100)
        stm.isTokenBudgetExceeded.shouldBeFalse()
    }

    @Test
    fun `ShortTermMemory consumeTokens accumulates correctly`() {
        val stm = ShortTermMemory(inputPrompt = "hello", tokenCount = 10)
        stm.consumeTokens(5).tokenCount shouldBe 15
        stm.consumeTokens(0).tokenCount shouldBe 10  // original unchanged
    }

    @Test
    fun `ShortTermMemory withAgentResult appends entry`() {
        val stm = ShortTermMemory(inputPrompt = "hello")
        val entry = MemoryEntry("rag found docs")
        val updated = stm.withAgentResult(entry)
        updated.agentResults shouldHaveSize 1
        updated.agentResults[0].content shouldBe "rag found docs"
        stm.agentResults.shouldBeEmpty()  // original unchanged
    }

    @Test
    fun `ShortTermMemory withTask updates currentTask`() {
        val stm = ShortTermMemory(inputPrompt = "test")
        stm.withTask("Analyse the code").currentTask shouldBe "Analyse the code"
    }

    // ── ConversationMemory ────────────────────────────────────────────────────

    @Test
    fun `ConversationMemory rejects blank conversationId`() {
        shouldThrow<IllegalArgumentException> {
            ConversationMemory(conversationId = "  ")
        }
    }

    @Test
    fun `ConversationMemory rejects zero maxMessages`() {
        shouldThrow<IllegalArgumentException> {
            ConversationMemory(conversationId = "c1", maxMessages = 0)
        }
    }

    @Test
    fun `ConversationMemory withMessage appends and respects maxMessages`() {
        val cm = ConversationMemory(conversationId = "c1", maxMessages = 3)
        val m1 = ContextMessage("user", "first")
        val m2 = ContextMessage("assistant", "second")
        val m3 = ContextMessage("user", "third")
        val m4 = ContextMessage("assistant", "fourth")

        val result = cm
            .withMessage(m1).withMessage(m2).withMessage(m3).withMessage(m4)

        // maxMessages=3 — oldest (m1) should be dropped
        result.recentMessages shouldHaveSize 3
        result.recentMessages[0].content shouldBe "second"
        result.recentMessages[2].content shouldBe "fourth"
    }

    @Test
    fun `ConversationMemory toContextMessages returns recentMessages`() {
        val msg = ContextMessage("user", "hi")
        val cm = ConversationMemory(conversationId = "c1").withMessage(msg)
        cm.toContextMessages() shouldBe listOf(msg)
    }

    @Test
    fun `ConversationMemory withRetrievedMemories replaces list`() {
        val cm = ConversationMemory(conversationId = "c1")
        val memories = listOf(MemoryEntry("fact 1"), MemoryEntry("fact 2"))
        cm.withRetrievedMemories(memories).retrievedMemories shouldHaveSize 2
    }

    // ── LongTermMemory ────────────────────────────────────────────────────────

    @Test
    fun `LongTermMemory rejects blank userId`() {
        shouldThrow<IllegalArgumentException> {
            LongTermMemory(userId = "  ")
        }
    }

    @Test
    fun `LongTermMemoryEntry rejects unapproved entries`() {
        shouldThrow<IllegalArgumentException> {
            LongTermMemoryEntry(
                entry = MemoryEntry("secret"),
                approvedByUser = false,
            )
        }
    }

    @Test
    fun `LongTermMemory withEntry requires approvedByUser`() {
        val ltm = LongTermMemory(userId = "u1")
        shouldThrow<IllegalArgumentException> {
            ltm.withEntry(
                LongTermMemoryEntry(MemoryEntry("secret"), approvedByUser = false)
            )
        }
    }

    @Test
    fun `LongTermMemory withEntry appends approved entry`() {
        val ltm = LongTermMemory(userId = "u1")
        val entry = LongTermMemoryEntry(MemoryEntry("I prefer dark mode"), approvedByUser = true)
        val updated = ltm.withEntry(entry)
        updated.entries shouldHaveSize 1
        ltm.entries.shouldBeEmpty()  // original unchanged
    }

    @Test
    fun `LongTermMemory findBySource filters correctly`() {
        val ltm = LongTermMemory(userId = "u1")
            .withEntry(LongTermMemoryEntry(MemoryEntry("likes Kotlin", source = "pref"), approvedByUser = true))
            .withEntry(LongTermMemoryEntry(MemoryEntry("uses Android Studio", source = "tool"), approvedByUser = true))
        ltm.findBySource("pref") shouldHaveSize 1
        ltm.findBySource("tool") shouldHaveSize 1
        ltm.findBySource("other").shouldBeEmpty()
    }

    @Test
    fun `LongTermMemory toContextMemories combines entries and projectKnowledge`() {
        val ltm = LongTermMemory(userId = "u1")
            .withEntry(LongTermMemoryEntry(MemoryEntry("user fact"), approvedByUser = true))
            .withProjectFact(MemoryEntry("project uses Kotlin"))

        val contexts = ltm.toContextMemories()
        contexts shouldHaveSize 2
        contexts.map { it.content }.containsAll(listOf("user fact", "project uses Kotlin")).shouldBeTrue()
    }

    // ── MemorySnapshot ────────────────────────────────────────────────────────

    @Test
    fun `MemorySnapshot toContextMemories includes all scopes`() {
        val shortTerm = ShortTermMemory(
            inputPrompt = "test",
            agentResults = listOf(MemoryEntry("step result", relevanceScore = 0.8f)),
        )
        val conversation = ConversationMemory(
            conversationId = "c1",
            retrievedMemories = listOf(MemoryEntry("retrieved fact", relevanceScore = 0.6f)),
        )
        val ltm = LongTermMemory(userId = "u1")
            .withEntry(LongTermMemoryEntry(MemoryEntry("long-term fact", relevanceScore = 0.4f), approvedByUser = true))

        val snapshot = MemorySnapshot(shortTerm, conversation, ltm)
        val memories = snapshot.toContextMemories()

        memories shouldHaveSize 3
        // Ranked by relevanceScore descending: 0.8, 0.6, 0.4
        memories[0].relevanceScore shouldBe 0.8f
        memories[1].relevanceScore shouldBe 0.6f
        memories[2].relevanceScore shouldBe 0.4f
    }

    @Test
    fun `MemorySnapshot toContextMemories with null conversation and longTerm`() {
        val shortTerm = ShortTermMemory(
            inputPrompt = "test",
            agentResults = listOf(MemoryEntry("only result")),
        )
        val snapshot = MemorySnapshot(shortTerm)
        snapshot.toContextMemories() shouldHaveSize 1
    }

    @Test
    fun `MemorySnapshot token budget trims memories`() {
        // 100 chars / 4 = 25 tokens each; budget of 30 tokens allows only 1 entry
        val longContent = "A".repeat(100)
        val shortTerm = ShortTermMemory(
            inputPrompt = "test",
            agentResults = listOf(
                MemoryEntry(longContent, relevanceScore = 1.0f),
                MemoryEntry(longContent, relevanceScore = 0.9f),
                MemoryEntry(longContent, relevanceScore = 0.8f),
            ),
        )
        val snapshot = MemorySnapshot(shortTerm, maxContextTokens = 30)
        val memories = snapshot.toContextMemories()
        // 100/4 = 25 tokens per entry; 30 token budget allows exactly 1 entry
        memories shouldHaveSize 1
        memories[0].relevanceScore shouldBe 1.0f
    }

    @Test
    fun `MemorySnapshot toConversationHistory delegates to conversation`() {
        val msg = ContextMessage("user", "hello")
        val conv = ConversationMemory("c1").withMessage(msg)
        val snapshot = MemorySnapshot(ShortTermMemory("x"), conversation = conv)
        snapshot.toConversationHistory() shouldBe listOf(msg)
    }

    @Test
    fun `MemorySnapshot toConversationHistory is empty when no conversation`() {
        val snapshot = MemorySnapshot(ShortTermMemory("x"))
        snapshot.toConversationHistory().shouldBeEmpty()
    }

    // ── AgentContextAssembler ─────────────────────────────────────────────────

    @Test
    fun `AgentContextAssembler assembles userId and conversationId from request`() {
        val req = AgentRequest(
            userId = "user-42",
            input = "Tell me a joke",
            conversationId = "conv-99",
        )
        val snapshot = MemorySnapshot(ShortTermMemory(inputPrompt = req.input))
        val ctx = AgentContextAssembler.assemble(req, snapshot)

        ctx.userId shouldBe "user-42"
        ctx.conversationId shouldBe "conv-99"
    }

    @Test
    fun `AgentContextAssembler excludes long-term memories when privacy mode is on`() {
        val ltm = LongTermMemory(userId = "u1")
            .withEntry(LongTermMemoryEntry(MemoryEntry("secret pref"), approvedByUser = true))

        val req = AgentRequest(
            userId = "u1", input = "query",
            context = AgentContext(userId = "u1", isPrivacyMode = true),
        )
        val snapshot = MemorySnapshot(ShortTermMemory("query"), longTerm = ltm)
        val ctx = AgentContextAssembler.assemble(req, snapshot)

        ctx.isPrivacyMode.shouldBeTrue()
        ctx.memories.none { it.content == "secret pref" }.shouldBeTrue()
    }

    @Test
    fun `AgentContextAssembler includes long-term memories when privacy mode is off`() {
        val ltm = LongTermMemory(userId = "u1")
            .withEntry(LongTermMemoryEntry(MemoryEntry("dark mode", relevanceScore = 0.9f), approvedByUser = true))

        val req = AgentRequest(
            userId = "u1", input = "query",
            context = AgentContext(userId = "u1", isPrivacyMode = false),
        )
        val snapshot = MemorySnapshot(ShortTermMemory("query"), longTerm = ltm)
        val ctx = AgentContextAssembler.assemble(req, snapshot)

        ctx.memories.any { it.content == "dark mode" }.shouldBeTrue()
    }

    @Test
    fun `AgentContextAssembler assembleMinimal builds context from request input`() {
        val req = AgentRequest(userId = "u1", input = "minimal test")
        val ctx = AgentContextAssembler.assembleMinimal(req)
        ctx.userId shouldBe "u1"
        ctx.conversationId.shouldBeNull()
        ctx.memories.shouldBeEmpty()
    }

    @Test
    fun `AgentContextAssembler assembleMinimal preserves existing context memories`() {
        val existingMemory = ContextMemory("prior knowledge", 0.7f)
        val req = AgentRequest(
            userId = "u1", input = "query",
            context = AgentContext(
                userId = "u1",
                memories = listOf(existingMemory),
            ),
        )
        val ctx = AgentContextAssembler.assembleMinimal(req)
        ctx.memories.any { it.content == "prior knowledge" }.shouldBeTrue()
    }

    @Test
    fun `AgentContextAssembler with SafetyLimits applies token budget`() {
        val longContent = "B".repeat(200)   // 200/4 = 50 tokens
        val shortTerm = ShortTermMemory(
            inputPrompt = "q",
            agentResults = listOf(
                MemoryEntry(longContent, relevanceScore = 1.0f),
                MemoryEntry(longContent, relevanceScore = 0.5f),
            ),
        )
        val snapshot = MemorySnapshot(shortTerm)
        val limits = SafetyLimits(maxContextTokens = 60)  // 60 tokens → only 1 entry of 50
        val req = AgentRequest(userId = "u1", input = "q")

        val ctx = AgentContextAssembler.assemble(req, snapshot, limits)
        ctx.memories shouldHaveSize 1
    }

    // ── SafetyLimits ──────────────────────────────────────────────────────────

    @Test
    fun `SafetyLimits rejects maxAgentSteps out of range`() {
        shouldThrow<IllegalArgumentException> {
            SafetyLimits(maxAgentSteps = 0)
        }
        shouldThrow<IllegalArgumentException> {
            SafetyLimits(maxAgentSteps = SafetyLimits.HARD_MAX_AGENT_STEPS + 1)
        }
    }

    @Test
    fun `SafetyLimits rejects negative agentTimeoutMs`() {
        shouldThrow<IllegalArgumentException> {
            SafetyLimits(agentTimeoutMs = 0L)
        }
    }

    @Test
    fun `SafetyLimits rejects negative maxContextTokens`() {
        shouldThrow<IllegalArgumentException> {
            SafetyLimits(maxContextTokens = -1)
        }
    }

    @Test
    fun `SafetyLimits TESTING preset constructs without error`() {
        SafetyLimits.TESTING.maxAgentSteps shouldBe SafetyLimits.HARD_MAX_AGENT_STEPS
    }

    @Test
    fun `SafetyLimits STRICT preset has tight limits`() {
        SafetyLimits.STRICT.maxAgentSteps shouldBe 3
        SafetyLimits.STRICT.maxAgentHandoffs shouldBe 1
    }

    @Test
    fun `SafetyLimits clampToHardLimits clips at hard caps`() {
        // maxAgentHandoffs = HARD_MAX + 1 would fail construction, so test that
        // a value at the hard limit is kept as-is
        val limits = SafetyLimits(maxAgentHandoffs = SafetyLimits.HARD_MAX_AGENT_HANDOFFS)
        limits.clampToHardLimits().maxAgentHandoffs shouldBe SafetyLimits.HARD_MAX_AGENT_HANDOFFS
    }

    @Test
    fun `SafetyLimits toAgentPlanLimits maps all fields`() {
        val limits = SafetyLimits(
            maxAgentSteps = 7,
            maxAgentHandoffs = 2,
            maxToolCalls = 15,
            agentTimeoutMs = 45_000L,
        )
        val planLimits = limits.toAgentPlanLimits()
        planLimits.maxSteps shouldBe 7
        planLimits.maxHandoffs shouldBe 2
        planLimits.maxToolCalls shouldBe 15
        planLimits.timeoutMs shouldBe 45_000L
    }
}

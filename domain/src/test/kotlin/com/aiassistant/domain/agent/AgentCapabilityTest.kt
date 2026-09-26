/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentCapabilityTest.kt
 * Purpose    : Unit tests for AgentCapability and Agent.canHandle default behaviour.
 *
 * Architecture Layer : Domain — agent sub-package (test)
 * Pattern Used       : JUnit4 + Kotest assertions
 * ============================================================
 */

package com.aiassistant.domain.agent

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

class AgentCapabilityTest {

    // Minimal stub agent used for canHandle tests
    private class StubAgent(
        override val capabilities: Set<AgentCapability>,
    ) : Agent {
        override val name = "stub"
        override val description = "test stub"
        override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> =
            emptyFlow()
    }

    private fun makeRequest(vararg caps: AgentCapability) =
        AgentRequest(userId = "u1", input = "test", capabilities = setOf(*caps))

    // ── canHandle default implementation ────────────────────────────────────

    @Test
    fun `canHandle returns true when request has no capability constraints`() {
        val agent = StubAgent(capabilities = setOf(AgentCapability.TEXT_GENERATION))
        val request = AgentRequest(userId = "u1", input = "hi")  // empty capabilities
        agent.canHandle(request).shouldBeTrue()
    }

    @Test
    fun `canHandle returns true when agent satisfies all required capabilities`() {
        val agent = StubAgent(capabilities = setOf(
            AgentCapability.TEXT_GENERATION,
            AgentCapability.STREAMING,
            AgentCapability.MEMORY_ACCESS,
        ))
        val request = makeRequest(AgentCapability.TEXT_GENERATION, AgentCapability.STREAMING)
        agent.canHandle(request).shouldBeTrue()
    }

    @Test
    fun `canHandle returns false when agent lacks a required capability`() {
        val agent = StubAgent(capabilities = setOf(AgentCapability.TEXT_GENERATION))
        val request = makeRequest(AgentCapability.TEXT_GENERATION, AgentCapability.TOOL_USE)
        agent.canHandle(request).shouldBeFalse()
    }

    @Test
    fun `canHandle returns false when agent has no capabilities and request requires some`() {
        val agent = StubAgent(capabilities = emptySet())
        val request = makeRequest(AgentCapability.CODE_ANALYSIS)
        agent.canHandle(request).shouldBeFalse()
    }

    @Test
    fun `canHandle returns true when capabilities match exactly`() {
        val caps = setOf(AgentCapability.ON_DEVICE_INFERENCE, AgentCapability.TEXT_GENERATION)
        val agent = StubAgent(capabilities = caps)
        val request = makeRequest(*caps.toTypedArray())
        agent.canHandle(request).shouldBeTrue()
    }

    // ── Enum completeness ───────────────────────────────────────────────────

    @Test
    fun `all capabilities are enumerated`() {
        // Ensures the enum entries list is non-empty and can be iterated
        AgentCapability.entries.size shouldBe 15
    }

    @Test
    fun `ON_DEVICE_INFERENCE is a valid capability`() {
        AgentCapability.entries.contains(AgentCapability.ON_DEVICE_INFERENCE).shouldBeTrue()
    }

    @Test
    fun `TOOL_USE is a valid capability`() {
        AgentCapability.entries.contains(AgentCapability.TOOL_USE).shouldBeTrue()
    }
}

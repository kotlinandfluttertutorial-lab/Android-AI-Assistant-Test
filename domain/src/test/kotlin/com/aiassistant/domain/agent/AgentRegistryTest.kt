/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentRegistryTest.kt
 * Purpose    : Unit tests for DefaultAgentRegistry.
 * ============================================================
 */
package com.aiassistant.domain.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

class AgentRegistryTest {

    private fun registry() = DefaultAgentRegistry()

    private fun stubAgent(
        name: String,
        vararg caps: AgentCapability,
    ) = object : Agent {
        override val name = name
        override val description = "stub"
        override val capabilities = setOf(*caps)
        override fun execute(r: AgentRequest, e: AgentExecution): Flow<AgentEvent> = emptyFlow()
    }

    @Test
    fun `empty registry has size 0 and isEmpty true`() {
        val reg = registry()
        reg.isEmpty.shouldBeTrue()
        reg.size shouldBe 0
    }

    @Test
    fun `register adds an agent`() {
        val reg = registry()
        reg.register(stubAgent("alpha"))
        reg.size shouldBe 1
        reg.isEmpty.shouldBeFalse()
    }

    @Test
    fun `register second agent with same name replaces first`() {
        val reg = registry()
        reg.register(stubAgent("alpha"))
        reg.register(stubAgent("alpha"))
        reg.size shouldBe 1
    }

    @Test
    fun `get returns registered agent`() {
        val reg = registry()
        val a = stubAgent("alpha")
        reg.register(a)
        reg.get("alpha") shouldBe a
    }

    @Test
    fun `get throws AgentNotFoundException for unknown name`() {
        val reg = registry()
        shouldThrow<AgentNotFoundException> { reg.get("unknown") }
    }

    @Test
    fun `getOrNull returns null for unknown name`() {
        val reg = registry()
        reg.getOrNull("unknown") shouldBe null
    }

    @Test
    fun `unregister removes agent`() {
        val reg = registry()
        reg.register(stubAgent("alpha"))
        reg.unregister("alpha")
        reg.isEmpty.shouldBeTrue()
    }

    @Test
    fun `unregister unknown name is no-op`() {
        val reg = registry()
        reg.unregister("does-not-exist") // must not throw
        reg.isEmpty.shouldBeTrue()
    }

    @Test
    fun `list returns all registered agents`() {
        val reg = registry()
        val a = stubAgent("alpha")
        val b = stubAgent("beta")
        reg.register(a)
        reg.register(b)
        val list = reg.list()
        list shouldHaveSize 2
        list shouldContain a
        list shouldContain b
    }

    @Test
    fun `findByCapability empty set returns all agents`() {
        val reg = registry()
        reg.register(stubAgent("alpha", AgentCapability.TEXT_GENERATION))
        reg.register(stubAgent("beta", AgentCapability.CODE_ANALYSIS))
        reg.findByCapability(emptySet()) shouldHaveSize 2
    }

    @Test
    fun `findByCapability filters by capability`() {
        val reg = registry()
        reg.register(stubAgent("chat", AgentCapability.TEXT_GENERATION, AgentCapability.STREAMING))
        reg.register(stubAgent("code", AgentCapability.CODE_ANALYSIS))
        val results = reg.findByCapability(setOf(AgentCapability.TEXT_GENERATION))
        results shouldHaveSize 1
        results[0].name shouldBe "chat"
    }

    @Test
    fun `findByCapability returns empty when no agent satisfies all caps`() {
        val reg = registry()
        reg.register(stubAgent("partial", AgentCapability.TEXT_GENERATION))
        reg.findByCapability(setOf(AgentCapability.TEXT_GENERATION, AgentCapability.TOOL_USE))
            .shouldBeEmpty()
    }

    @Test
    fun `multiple agents with required capability all returned`() {
        val reg = registry()
        reg.register(stubAgent("a", AgentCapability.STREAMING))
        reg.register(stubAgent("b", AgentCapability.STREAMING))
        reg.register(stubAgent("c", AgentCapability.CODE_ANALYSIS))
        reg.findByCapability(setOf(AgentCapability.STREAMING)) shouldHaveSize 2
    }
}

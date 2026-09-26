/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentRouterTest.kt
 * Purpose    : Unit tests for DefaultAgentRouter routing logic.
 * ============================================================
 */
package com.aiassistant.domain.agent

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

class AgentRouterTest {

    private val router = DefaultAgentRouter()

    private fun registry(vararg agents: Agent): DefaultAgentRegistry {
        val reg = DefaultAgentRegistry()
        agents.forEach { reg.register(it) }
        return reg
    }

    private fun stubAgent(
        name: String,
        vararg caps: AgentCapability,
    ) = object : Agent {
        override val name = name
        override val description = "stub"
        override val capabilities = setOf(*caps)
        override fun execute(r: AgentRequest, e: AgentExecution): Flow<AgentEvent> = emptyFlow()
    }

    private fun req(
        input: String = "hello",
        vararg caps: AgentCapability,
        conversationId: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) = AgentRequest(
        userId = "u1",
        input = input,
        capabilities = setOf(*caps),
        conversationId = conversationId,
        metadata = metadata,
    )

    // ── Empty registry ───────────────────────────────────────────────────────

    @Test
    fun `empty registry returns NoAgentFound`() {
        val result = router.route(req(), registry())
        (result is RoutingOutcome.NoAgentFound).shouldBeTrue()
    }

    // ── Explicit name ────────────────────────────────────────────────────────

    @Test
    fun `explicit agent_name routes to that agent`() {
        val a = stubAgent("alpha")
        val result = router.route(
            req(metadata = mapOf(DefaultAgentRouter.METADATA_KEY_AGENT_NAME to "alpha")),
            registry(a),
        )
        (result as RoutingOutcome.Routed).agent shouldBe a
        result.reason shouldBe "explicit_name:alpha"
    }

    @Test
    fun `explicit agent_name not registered returns NoAgentFound`() {
        val result = router.route(
            req(metadata = mapOf(DefaultAgentRouter.METADATA_KEY_AGENT_NAME to "missing")),
            registry(stubAgent("alpha")),
        )
        (result is RoutingOutcome.NoAgentFound).shouldBeTrue()
    }

    // ── Capability match ─────────────────────────────────────────────────────

    @Test
    fun `capability match selects agent with required capability`() {
        val code = stubAgent("code", AgentCapability.CODE_ANALYSIS)
        val chat = stubAgent("chat", AgentCapability.TEXT_GENERATION)
        val result = router.route(
            req(caps = arrayOf(AgentCapability.CODE_ANALYSIS)),
            registry(chat, code),
        )
        (result as RoutingOutcome.Routed).agent shouldBe code
    }

    @Test
    fun `no agent with required capability returns NoAgentFound`() {
        val chat = stubAgent("chat", AgentCapability.TEXT_GENERATION)
        val result = router.route(
            req(caps = arrayOf(AgentCapability.TOOL_USE)),
            registry(chat),
        )
        (result is RoutingOutcome.NoAgentFound).shouldBeTrue()
    }

    // ── Attachment routing ───────────────────────────────────────────────────

    @Test
    fun `image attachment routes to IMAGE_UNDERSTANDING agent`() {
        val vision = stubAgent("vision", AgentCapability.IMAGE_UNDERSTANDING)
        val chat = stubAgent("chat", AgentCapability.TEXT_GENERATION)
        val result = router.route(
            req(metadata = mapOf(DefaultAgentRouter.METADATA_KEY_ATTACHMENT_TYPE to "image")),
            registry(chat, vision),
        )
        (result as RoutingOutcome.Routed).agent shouldBe vision
        result.reason shouldBe "attachment_type:image"
    }

    // ── Conversation context ─────────────────────────────────────────────────

    @Test
    fun `conversation context prefers conversational agent`() {
        val conv = stubAgent(DefaultAgentRouter.AGENT_NAME_CONVERSATIONAL, AgentCapability.TEXT_GENERATION)
        val code = stubAgent("code", AgentCapability.CODE_ANALYSIS)
        val result = router.route(
            req(conversationId = "conv-1"),
            registry(code, conv),
        )
        (result as RoutingOutcome.Routed).agent shouldBe conv
        result.reason shouldBe "conversation_context"
    }

    // ── First capable ─────────────────────────────────────────────────────────

    @Test
    fun `first capable agent selected when no other heuristic matches`() {
        val a = stubAgent("alpha", AgentCapability.TEXT_GENERATION)
        val result = router.route(req(), registry(a))
        (result as RoutingOutcome.Routed).agent shouldBe a
        result.reason shouldBe "first_capable"
    }

    @Test
    fun `no capable agent returns NoAgentFound`() {
        // Agent that rejects all requests
        val picky = object : Agent {
            override val name = "picky"
            override val description = "rejects everything"
            override val capabilities = emptySet<AgentCapability>()
            override fun canHandle(request: AgentRequest) = false
            override fun execute(r: AgentRequest, e: AgentExecution): Flow<AgentEvent> = emptyFlow()
        }
        val result = router.route(req(), registry(picky))
        (result is RoutingOutcome.NoAgentFound).shouldBeTrue()
    }
}

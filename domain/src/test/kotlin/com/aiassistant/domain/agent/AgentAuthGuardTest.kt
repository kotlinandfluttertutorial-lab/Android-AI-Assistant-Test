/*
 * Phase 10 — Production Hardening
 * Unit tests for AgentAuthGuard and AgentGateway.UNAUTHENTICATED_USER_ID sentinel.
 */
package com.aiassistant.domain.agent

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AgentAuthGuardTest {

    private fun req(userId: String) = AgentRequest(
        userId = userId,
        input = "test",
    )

    // ── isAuthenticated ───────────────────────────────────────────────────────

    @Test
    fun `isAuthenticated true for valid UUID-like userId`() {
        AgentAuthGuard.isAuthenticated("d4e5f6a7-b8c9-0d1e-2f3a-4b5c6d7e8f9a").shouldBeTrue()
    }

    @Test
    fun `isAuthenticated true for any non-sentinel non-blank string`() {
        AgentAuthGuard.isAuthenticated("user-123").shouldBeTrue()
        AgentAuthGuard.isAuthenticated("abc").shouldBeTrue()
    }

    @Test
    fun `isAuthenticated false for blank`() {
        AgentAuthGuard.isAuthenticated("").shouldBeFalse()
        AgentAuthGuard.isAuthenticated("   ").shouldBeFalse()
    }

    @Test
    fun `isAuthenticated false for anonymous`() {
        AgentAuthGuard.isAuthenticated("anonymous").shouldBeFalse()
        AgentAuthGuard.isAuthenticated("ANONYMOUS").shouldBeFalse()
    }

    @Test
    fun `isAuthenticated false for guest`() {
        AgentAuthGuard.isAuthenticated("guest").shouldBeFalse()
    }

    @Test
    fun `isAuthenticated false for unknown`() {
        AgentAuthGuard.isAuthenticated("unknown").shouldBeFalse()
    }

    @Test
    fun `isAuthenticated false for placeholder`() {
        AgentAuthGuard.isAuthenticated("placeholder").shouldBeFalse()
    }

    @Test
    fun `isAuthenticated false for unauthenticated sentinel`() {
        AgentAuthGuard.isAuthenticated("__unauthenticated__").shouldBeFalse()
    }

    // ── validate ──────────────────────────────────────────────────────────────

    @Test
    fun `validate returns Authenticated for valid userId`() {
        val result = AgentAuthGuard.validate(req("user-42"))
        result.shouldBeInstanceOf<AgentAuthResult.Authenticated>()
        (result as AgentAuthResult.Authenticated).userId shouldBe "user-42"
    }

    @Test
    fun `validate returns Unauthenticated for blank userId via isAuthenticated`() {
        // AgentRequest.userId validates non-blank at construction so we test
        // the blank path via isAuthenticated() directly
        AgentAuthGuard.isAuthenticated("").shouldBeFalse()
        AgentAuthGuard.isAuthenticated("   ").shouldBeFalse()
    }

    @Test
    fun `validate returns Unauthenticated for anonymous sentinel`() {
        val result = AgentAuthGuard.validate(req(ANONYMOUS_USER_ID))
        result.shouldBeInstanceOf<AgentAuthResult.Unauthenticated>()
    }

    @Test
    fun `validate returns Unauthenticated for guest sentinel`() {
        val result = AgentAuthGuard.validate(req("guest"))
        result.shouldBeInstanceOf<AgentAuthResult.Unauthenticated>()
    }

    // ── Orchestrator auth enforcement ─────────────────────────────────────────

    @Test
    fun `orchestrator rejects anonymous userId with UNAUTHENTICATED`() = runTest {
        val agent = stubAgent("alpha")
        val reg = DefaultAgentRegistry().also { it.register(agent) }
        val orc = DefaultAgentOrchestrator(
            registry = reg,
            router = DefaultAgentRouter(),
            planner = DefaultAgentPlanner(),
            maxConcurrentPerUser = 0,  // disable concurrency limit for test
        )

        // Anonymous sentinel — should be rejected
        val anonRequest = AgentRequest(
            userId = ANONYMOUS_USER_ID,
            input = "test",
            metadata = mapOf("agent_name" to "alpha"),
        )
        val events = orc.execute(anonRequest).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().first()
        failed.result.error?.code shouldBe "UNAUTHENTICATED"
    }

    @Test
    fun `orchestrator allows authenticated userId`() = runTest {
        val agent = stubAgent("alpha")
        val reg = DefaultAgentRegistry().also { it.register(agent) }
        val orc = DefaultAgentOrchestrator(
            registry = reg,
            router = DefaultAgentRouter(),
            planner = DefaultAgentPlanner(),
            maxConcurrentPerUser = 0,
        )

        val validRequest = AgentRequest(
            userId = "user-uuid-1234",
            input = "test",
            metadata = mapOf("agent_name" to "alpha"),
        )
        val events = orc.execute(validRequest).toList()

        events.filterIsInstance<AgentEvent.Failed>()
            .none { it.result.error?.code == "UNAUTHENTICATED" }.shouldBeTrue()
        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
    }

    // ── Concurrency limit enforcement ─────────────────────────────────────────

    @Test
    fun `MAX_CONCURRENT_PER_USER constant is positive`() {
        // Verify the constant is configured correctly (5 by default)
        (DefaultAgentOrchestrator.MAX_CONCURRENT_PER_USER > 0).shouldBeTrue()
    }

    @Test
    fun `orchestrator with limit 0 allows unlimited concurrent requests`() = runTest {
        val agent = stubAgent("alpha")
        val reg = DefaultAgentRegistry().also { it.register(agent) }
        val orc = DefaultAgentOrchestrator(
            registry = reg,
            router = DefaultAgentRouter(),
            planner = DefaultAgentPlanner(),
            maxConcurrentPerUser = 0,  // disabled — unlimited
        )

        // Two rapid sequential requests from same user should both succeed
        val events1 = orc.execute(AgentRequest(userId = "u1", input = "first",
            metadata = mapOf("agent_name" to "alpha"))).toList()
        val events2 = orc.execute(AgentRequest(userId = "u1", input = "second",
            metadata = mapOf("agent_name" to "alpha"))).toList()

        events1.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
        events2.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
        // No concurrency errors
        events1.filterIsInstance<AgentEvent.Failed>()
            .none { it.result.error?.code == "CONCURRENCY_LIMIT_EXCEEDED" }.shouldBeTrue()
        events2.filterIsInstance<AgentEvent.Failed>()
            .none { it.result.error?.code == "CONCURRENCY_LIMIT_EXCEEDED" }.shouldBeTrue()
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun stubAgent(name: String) = object : Agent {
        override val name = name
        override val description = "stub"
        override val capabilities = setOf(AgentCapability.TEXT_GENERATION)
        override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> = flow {
            emit(AgentEvent.Completed(
                AgentResult(execution.executionId, request.requestId, name, AgentStatus.COMPLETED, "ok")
            ))
        }
    }
}

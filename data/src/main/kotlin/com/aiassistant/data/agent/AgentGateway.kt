/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : AgentGateway.kt
 * Purpose    : Data-layer implementation of AgentGatewayRepository.
 *              Bridges ChatDetailViewModel → AgentOrchestrator → ChatAgent.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Repository implementation (Gateway adapter)
 *
 * Key Concepts:
 *   - Implements AgentGatewayRepository (domain interface)
 *   - ChatDetailViewModel depends only on the domain interface; it never
 *     sees AgentGateway, AgentOrchestrator, or ChatAgent directly
 *   - Builds an AgentRequest from the caller's parameters and delegates
 *     to DefaultAgentOrchestrator.execute()
 *   - The orchestrator routes to ChatAgent (registered at init time)
 *   - AgentGateway is a @Singleton so the registry is initialised once
 *   - All existing streaming behavior is preserved: the AgentEvent stream
 *     is a transparent translation of the existing StreamEvent stream
 *
 * Dependencies: domain (AgentGatewayRepository, AgentOrchestrator, AgentRegistry,
 *               AgentRouter, AgentPlanner, AgentContext, AgentRequest, AgentEvent),
 *               data (ChatAgent)
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.domain.agent.AgentContext
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentGatewayRepository
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.DefaultAgentOrchestrator
import com.aiassistant.domain.agent.DefaultAgentPlanner
import com.aiassistant.domain.agent.DefaultAgentRegistry
import com.aiassistant.domain.agent.DefaultAgentRouter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Production implementation of [AgentGatewayRepository].
 *
 * ## Initialisation
 * At construction time this class:
 * 1. Creates a [DefaultAgentRegistry] and registers [ChatAgent] under
 *    the name `"conversational"`.
 * 2. Creates a [DefaultAgentOrchestrator] with the registry, router, and planner.
 *
 * This is idempotent — the registry is rebuilt on each @Singleton creation
 * (i.e. once per process).
 *
 * ## No existing code is modified
 * [ChatDetailViewModel] is only extended with a new optional constructor
 * parameter (see task #4).  All existing ViewModel logic, DI bindings,
 * and Retrofit calls remain completely unchanged.
 *
 * @param chatAgent   The [ChatAgent] registered as the conversational agent.
 */
@Singleton
class AgentGateway @Inject constructor(
    private val chatAgent: ChatAgent,
) : AgentGatewayRepository {

    // ── Registry and orchestrator ────────────────────────────────────────────

    private val registry = DefaultAgentRegistry().also { reg ->
        reg.register(chatAgent)
    }

    private val orchestrator = DefaultAgentOrchestrator(
        registry = registry,
        router = DefaultAgentRouter(),
        planner = DefaultAgentPlanner(),
    )

    // ── AgentGatewayRepository ───────────────────────────────────────────────

    override fun executeChat(
        conversationId: String,
        content: String,
        provider: String,
        context: AgentContext?,
    ): Flow<AgentEvent> {
        val request = AgentRequest(
            userId = resolveUserId(context),
            input = content,
            conversationId = conversationId,
            provider = provider.takeIf { it.isNotBlank() },
            capabilities = emptySet(),   // no cap constraint — ChatAgent handles all
            context = context,
            metadata = mapOf(
                ChatAgent.METADATA_AGENT_NAME to ChatAgent.NAME,
            ),
        )
        return orchestrator.execute(request)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Derives a userId string from the pre-assembled context when available.
     * Falls back to a synthetic placeholder that does not cause validation failures
     * (AgentRequest.userId must not be blank).
     */
    private fun resolveUserId(context: AgentContext?): String =
        context?.userId?.takeIf { it.isNotBlank() }
            ?: ANONYMOUS_USER_ID

    companion object {
        private const val ANONYMOUS_USER_ID = "anonymous"
    }
}

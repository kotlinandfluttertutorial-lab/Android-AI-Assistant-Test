/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentRouter.kt
 * Purpose    : Deterministic routing of AgentRequests to registered Agents.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface + default implementation
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Routing is deterministic — no autonomous LLM-based classification
 *   - Priority order: explicit name → capability match → attachments →
 *     conversation context → first capable agent → error
 *   - The orchestrator calls this; the orchestrator contains no hardcoded
 *     routing logic itself
 *
 * Design Decision:
 *   DefaultAgentRouter is a pure function of the registry state and request
 *   fields. This makes routing fully unit-testable without mocks.
 *
 * Dependencies: domain agent models, AgentRegistry
 * ============================================================
 */

package com.aiassistant.domain.agent

/**
 * Outcome of [AgentRouter.route].
 */
sealed class RoutingOutcome {
    /** The request was successfully routed to [agent]. */
    data class Routed(
        val agent: Agent,
        val reason: String,
    ) : RoutingOutcome()

    /** No registered agent can handle this request. */
    data class NoAgentFound(
        val reason: String,
    ) : RoutingOutcome()
}

/**
 * Routes an [AgentRequest] to the most appropriate registered [Agent].
 *
 * Routing must be **deterministic** — given the same registry state and
 * request, the same agent is always selected.  No LLM calls are made.
 */
interface AgentRouter {

    /**
     * Select the best [Agent] from [registry] for [request].
     *
     * Priority order:
     * 1. **Explicit name** — if `request.metadata["agent_name"]` is set,
     *    return that agent or [RoutingOutcome.NoAgentFound].
     * 2. **Capability match** — agents that satisfy all requested capabilities,
     *    then filtered by [Agent.canHandle].
     * 3. **Attachment routing** — if context indicates an image attachment,
     *    prefer agents with [AgentCapability.IMAGE_UNDERSTANDING].
     * 4. **Conversation context** — if a conversationId is present and the
     *    registry has a "conversational" agent, prefer it.
     * 5. **First capable** — first agent in [AgentRegistry.list] where
     *    [Agent.canHandle] returns true.
     * 6. **No agent found** — return [RoutingOutcome.NoAgentFound].
     *
     * @param request  The request to route.
     * @param registry The current agent registry.
     */
    fun route(request: AgentRequest, registry: AgentRegistry): RoutingOutcome
}

// ── Default implementation ───────────────────────────────────────────────────

/**
 * Default [AgentRouter] — fully deterministic, no LLM calls.
 */
class DefaultAgentRouter : AgentRouter {

    override fun route(request: AgentRequest, registry: AgentRegistry): RoutingOutcome {
        if (registry.isEmpty) {
            return RoutingOutcome.NoAgentFound("Agent registry is empty.")
        }

        // ── 1. Explicit agent name in metadata ─────────────────────────────
        val explicitName = request.metadata[METADATA_KEY_AGENT_NAME]
        if (!explicitName.isNullOrBlank()) {
            val agent = registry.getOrNull(explicitName)
                ?: return RoutingOutcome.NoAgentFound(
                    "Explicitly requested agent '$explicitName' is not registered."
                )
            return RoutingOutcome.Routed(agent, "explicit_name:$explicitName")
        }

        // ── 2. Capability match ─────────────────────────────────────────────
        if (request.capabilities.isNotEmpty()) {
            val candidates = registry.findByCapability(request.capabilities.toSet())
                .filter { it.canHandle(request) }
            if (candidates.isNotEmpty()) {
                val chosen = candidates.first()
                return RoutingOutcome.Routed(chosen, "capability_match:${request.capabilities}")
            }
            return RoutingOutcome.NoAgentFound(
                "No agent satisfies requested capabilities: ${request.capabilities}."
            )
        }

        // ── 3. Attachment routing — image → IMAGE_UNDERSTANDING agent ───────
        val hasImageAttachment = request.metadata[METADATA_KEY_ATTACHMENT_TYPE] == "image"
        if (hasImageAttachment) {
            val imageAgent = registry
                .findByCapability(setOf(AgentCapability.IMAGE_UNDERSTANDING))
                .filter { it.canHandle(request) }
                .firstOrNull()
            if (imageAgent != null) {
                return RoutingOutcome.Routed(imageAgent, "attachment_type:image")
            }
        }

        // ── 4. Conversation context → prefer "conversational" agent ─────────
        if (request.conversationId != null) {
            val conversational = registry.getOrNull(AGENT_NAME_CONVERSATIONAL)
            if (conversational != null && conversational.canHandle(request)) {
                return RoutingOutcome.Routed(conversational, "conversation_context")
            }
        }

        // ── 5. First capable agent ──────────────────────────────────────────
        val firstCapable = registry.list().firstOrNull { it.canHandle(request) }
        if (firstCapable != null) {
            return RoutingOutcome.Routed(firstCapable, "first_capable")
        }

        return RoutingOutcome.NoAgentFound(
            "No registered agent can handle request (${registry.size} agents checked)."
        )
    }

    companion object {
        /** Metadata key for explicit agent name routing. */
        const val METADATA_KEY_AGENT_NAME = "agent_name"

        /** Metadata key that signals the type of attachment in the request. */
        const val METADATA_KEY_ATTACHMENT_TYPE = "attachment_type"

        /** Well-known name of the default conversational agent. */
        const val AGENT_NAME_CONVERSATIONAL = "conversational"
    }
}

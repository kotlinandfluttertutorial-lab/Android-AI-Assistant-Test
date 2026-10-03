/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ChatExecutionMode.kt
 * Purpose    : User-visible execution mode for the Chat screen.
 *              Three distinct modes let the user control how their
 *              message is processed without exposing internal agent
 *              routing details.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Enum + mapper (maps to AgentMode)
 *
 * Design Decision:
 *   A separate enum (rather than reusing AgentMode directly) keeps the
 *   UX contract stable even if internal AgentMode values change.
 *   The toAgentMode() mapper is the single translation point.
 *
 * Dependencies: pure Kotlin — zero Android/framework imports
 * ============================================================
 */

package com.aiassistant.domain.agent

/**
 * The three execution modes available to the user in the Chat screen.
 *
 * | Mode        | What happens                                                     |
 * |-------------|------------------------------------------------------------------|
 * | DIRECT_LLM  | Message sent straight to the configured LLM provider.           |
 * |             | Fastest path; no document retrieval, no tool calls.             |
 * | RAG         | Message routed through the RAG pipeline.  Relevant document     |
 * |             | chunks are retrieved and injected as context before the LLM      |
 * |             | generates a grounded answer.  Source citations are returned.    |
 * | AGENT       | Message handed to the full agent orchestration layer.           |
 * |             | The agent may call MCP tools, retrieve documents, and reason    |
 * |             | across multiple steps before producing a final response.        |
 */
enum class ChatExecutionMode {

    /**
     * Direct LLM call — no retrieval, no tool use.
     * Maps to [AgentMode.CHAT] so the orchestrator routes to the conversational agent.
     */
    DIRECT_LLM,

    /**
     * RAG (Retrieval-Augmented Generation) mode.
     * Maps to [AgentMode.DOCUMENT] so the orchestrator routes to the RAG agent.
     * The response includes [AgentCitation] source references.
     */
    RAG,

    /**
     * Full agent execution mode — multi-step reasoning with tool calls.
     * Maps to [AgentMode.AUTO] so the orchestrator selects the best agent and
     * may delegate to sub-agents, call MCP tools, and retrieve documents as needed.
     */
    AGENT,
    ;

    /**
     * Returns the [AgentMode] that corresponds to this execution mode.
     *
     * Used by [com.aiassistant.feature.chat.ChatDetailViewModel] when calling
     * [com.aiassistant.domain.agent.AgentGatewayRepository.executeChat].
     */
    fun toAgentMode(): AgentMode = when (this) {
        DIRECT_LLM -> AgentMode.CHAT
        RAG        -> AgentMode.DOCUMENT
        AGENT      -> AgentMode.AUTO
    }

    companion object {

        /** Human-readable label shown in the mode-selector chip row. */
        fun displayName(mode: ChatExecutionMode): String = when (mode) {
            DIRECT_LLM -> "Direct"
            RAG        -> "RAG"
            AGENT      -> "Agent"
        }

        /**
         * Single-line description shown as a chip tooltip or accessibility hint.
         */
        fun description(mode: ChatExecutionMode): String = when (mode) {
            DIRECT_LLM -> "Send directly to the language model"
            RAG        -> "Search documents, then answer"
            AGENT      -> "Multi-step reasoning with tools"
        }

        /** Returns all modes in display order. */
        val all: List<ChatExecutionMode> get() = listOf(DIRECT_LLM, RAG, AGENT)
    }
}

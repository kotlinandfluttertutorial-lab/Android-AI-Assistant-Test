/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentContext.kt
 * Purpose    : Runtime context injected into an Agent at execution time.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Immutable value object (data class)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Assembles all "ambient" information the agent needs without the agent
 *     having to query each source itself — separation of assembly from execution
 *   - Serialisable for caching assembled context across process restarts
 *
 * Design Decision:
 *   AgentContext is assembled by an orchestration layer (future AgentService /
 *   AgentOrchestrator) before the Agent.execute() call.  The agent never directly
 *   calls MemoryService, PersonaRepository, or any other cross-cutting service —
 *   it receives a fully assembled context.  This keeps agents stateless and
 *   independently testable.
 *
 * Dependencies: kotlinx-serialization-json
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

/**
 * All ambient information available to an [Agent] during execution.
 *
 * The orchestration layer assembles this before calling [Agent.execute].
 * Every field is optional so partial contexts can be built incrementally
 * (e.g. memory retrieval may time out — the agent still runs without it).
 *
 * @param userId            The ID of the user on whose behalf the agent is running.
 * @param conversationId    Conversation to which this execution is attached, if any.
 * @param conversationHistory Recent messages from the conversation (newest last).
 *                           Empty if this is a standalone (non-conversational) task.
 * @param memories          Semantically relevant user memories retrieved for this query.
 *                          Empty if memory retrieval failed or privacy mode is active.
 * @param personaSystemPrompt System prompt injected by the active [Persona], if any.
 * @param ragDocumentIds    Document IDs the agent should restrict retrieval to.
 *                          Empty = search across all user documents.
 * @param availableTools    Names of MCP tools the agent is permitted to invoke.
 *                          Empty = no tool access.
 * @param isPrivacyMode     When true, the agent must not persist any new memories.
 * @param isOffline         When true, the agent must not make outbound network calls.
 * @param extraContext      Additional free-form key-value context injected by the caller
 *                          (e.g. current screen name, selected text, clipboard content).
 */
@Serializable
data class AgentContext(
    val userId: String,
    val conversationId: String? = null,
    val conversationHistory: List<ContextMessage> = emptyList(),
    val memories: List<ContextMemory> = emptyList(),
    val personaSystemPrompt: String? = null,
    val ragDocumentIds: List<String> = emptyList(),
    val availableTools: List<String> = emptyList(),
    val isPrivacyMode: Boolean = false,
    val isOffline: Boolean = false,
    val extraContext: Map<String, String> = emptyMap(),
) {
    /**
     * Returns a copy of this context with [memories] appended.
     *
     * Useful when memory retrieval completes asynchronously after initial context assembly.
     */
    fun withMemories(additional: List<ContextMemory>): AgentContext =
        copy(memories = memories + additional)

    /**
     * Returns a copy of this context with [tools] added to [availableTools].
     */
    fun withTools(tools: List<String>): AgentContext =
        copy(availableTools = (availableTools + tools).distinct())
}

/**
 * A single turn in the conversation history injected into [AgentContext].
 *
 * @param role    Who authored the message ("user" | "assistant" | "system" | "tool").
 * @param content The text content of the message.
 */
@Serializable
data class ContextMessage(
    val role: String,
    val content: String,
)

/**
 * A single long-term memory entry injected into [AgentContext].
 *
 * @param content         The text of the memory.
 * @param relevanceScore  Cosine similarity score against the current query (0.0–1.0).
 */
@Serializable
data class ContextMemory(
    val content: String,
    val relevanceScore: Float,
)

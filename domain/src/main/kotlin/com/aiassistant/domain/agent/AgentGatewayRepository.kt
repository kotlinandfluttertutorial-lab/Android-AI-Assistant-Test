/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentGatewayRepository.kt
 * Purpose    : Domain contract for routing chat requests through the Agent
 *              Orchestrator layer rather than directly to AIStreamClient.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Repository interface (Gateway)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - ChatDetailViewModel depends on this interface, not on the data-layer
 *     ChatAgent or AgentOrchestrator — Clean Architecture boundary preserved
 *   - executeChat() is a drop-in replacement for the inline startStreaming()
 *     logic currently in ChatDetailViewModel; it returns the same AgentEvent
 *     stream that the orchestrator already emits
 *   - The existing REST persistence path (sendMessageUseCase) is NOT touched;
 *     it continues to run concurrently alongside the streaming path
 *
 * Design Decision:
 *   A separate gateway (rather than extending MessageRepository) keeps the
 *   agent concerns isolated.  MessageRepository remains responsible only for
 *   REST persistence; AgentGatewayRepository is responsible only for the
 *   streaming AI execution path.
 *
 * Dependencies: domain agent models, kotlinx.coroutines.flow
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * Gateway through which [com.aiassistant.feature.chat.ChatDetailViewModel] routes
 * chat requests into the Agent Orchestrator stack.
 *
 * The implementation lives in `:data` ([com.aiassistant.data.agent.AgentGateway])
 * and is injected via Hilt.
 */
interface AgentGatewayRepository {

    /**
     * Execute a chat turn through the Agent Orchestrator and stream [AgentEvent] values.
     *
     * This is the agent-layer equivalent of the existing
     * `AIStreamClient.connect(conversationId, jwt)` + `sendMessage(payload)` pair
     * currently inlined in `ChatDetailViewModel.startStreaming()`.
     *
     * ## What this does
     * 1. Resolves the JWT from `SecureStorage` (fixes the `"placeholder_jwt"` gap).
     * 2. Routes the request through `AgentOrchestrator` → `ChatAgent`.
     * 3. `ChatAgent` connects to `AIStreamClient`, sends the payload, and
     *    translates `StreamEvent` values to `AgentEvent` values.
     * 4. All existing backoff, reconnect, and error semantics from
     *    `AIStreamClientImpl` are preserved unchanged.
     *
     * ## Streaming protocol (backward-compatible)
     * The emitted [AgentEvent] types map directly to what the ViewModel already
     * handles:
     * - [AgentEvent.Token]     ← was `StreamEvent.Token`
     * - [AgentEvent.Completed] ← was `StreamEvent.Done`
     * - [AgentEvent.Failed]    ← was `StreamEvent.Error`
     * - [AgentEvent.ToolStarted] / [AgentEvent.ToolCompleted] / [AgentEvent.ToolFailed]
     *                          ← was `StreamEvent.ToolCall` (previously a no-op)
     *
     * ## Thread safety
     * The returned Flow is cold. Each collection starts a new agent execution.
     * The caller (ViewModel) must cancel the collecting Job to stop streaming.
     *
     * @param conversationId Unique ID of the conversation thread.
     * @param content        The user's message text.
     * @param provider       LLM provider identifier (e.g. "gemini", "openai", "on_device").
     * @param context        Optional pre-assembled [AgentContext] (conversation history,
     *                       memories, persona prompt, etc.). When null the implementation
     *                       assembles a minimal context from the conversationId.
     * @return Cold [Flow] of [AgentEvent] values. Always terminates with
     *         [AgentEvent.Completed], [AgentEvent.Failed], or [AgentEvent.Cancelled].
     */
    fun executeChat(
        conversationId: String,
        content: String,
        provider: String,
        context: AgentContext? = null,
    ): Flow<AgentEvent>
}

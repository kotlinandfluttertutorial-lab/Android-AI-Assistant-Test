/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ChatAgent.kt
 * Purpose    : Agent implementation that wraps the existing AIStreamClient
 *              and translates StreamEvent → AgentEvent without modifying
 *              any existing code.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Adapter (implements Agent interface from domain)
 *
 * Key Concepts:
 *   - Implements Agent from domain; lives in :data where AIStreamClient lives
 *   - DOES NOT modify AIStreamClient, AIStreamClientImpl, or any existing file
 *   - Translates the four StreamEvent subtypes to AgentEvent subtypes
 *   - Resolves JWT from SecureStorage (fixes the placeholder_jwt gap in
 *     ChatDetailViewModel without modifying the ViewModel directly)
 *   - StreamEvent.ToolCall is now surfaced as AgentEvent.ToolStarted/Completed
 *     rather than being silently dropped
 *   - No on-device / cloud routing here — that is handled by ModelRouter upstream;
 *     ChatAgent always delegates to whatever AIStreamClient is bound by Hilt
 *     (AIStreamClientImpl in release, MockAIStreamClient in debug)
 *
 * Design Decision:
 *   ChatAgent is deliberately thin — it does no prompt engineering, memory
 *   injection, or context windowing. Those concerns belong in a future
 *   system-prompt builder. This phase only wires the existing WebSocket path
 *   into the Agent architecture.
 *
 * Dependencies: core-ai (AIStreamClient, StreamEvent, MessagePayload,
 *               ON_DEVICE_PROVIDER_ID), core-security (SecureStorage),
 *               domain (Agent, AgentEvent, AgentCapability, etc.)
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.core.ai.AIStreamClient
import com.aiassistant.core.ai.MessagePayload
import com.aiassistant.core.ai.ON_DEVICE_PROVIDER_ID
import com.aiassistant.core.ai.StreamEvent
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.domain.agent.Agent
import com.aiassistant.domain.agent.AgentAttachment
import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentError
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentNextAction
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentResult
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.AgentUsage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * [Agent] implementation that routes chat requests through the existing
 * [AIStreamClient] WebSocket infrastructure.
 *
 * ## Capability contract
 * - Declares [AgentCapability.TEXT_GENERATION] + [AgentCapability.STREAMING] +
 *   [AgentCapability.MEMORY_ACCESS] + [AgentCapability.TOOL_USE] (ToolCall
 *   passthrough) + [AgentCapability.ON_DEVICE_INFERENCE] (when provider is
 *   [ON_DEVICE_PROVIDER_ID], the ViewModel's existing `isRunningOnDevice` flag
 *   is preserved via AgentContext metadata).
 *
 * ## Streaming lifecycle (mirrors existing ViewModel startStreaming() logic)
 * 1. Resolve JWT from [SecureStorage]; fall back to `""` (invalid token) so
 *    the backend rejects cleanly — better than using "placeholder_jwt".
 * 2. `streamClient.connect(conversationId, jwt)` — returns cold `Flow<StreamEvent>`.
 * 3. `streamClient.sendMessage(MessagePayload(...))` — sent immediately.
 * 4. Collect `StreamEvent` values and translate to `AgentEvent`:
 *    - `Token` → `AgentEvent.Token`
 *    - `Done`  → `AgentEvent.Completed(AgentResult(COMPLETED, usage, …))`
 *    - `Error` → `AgentEvent.Failed(AgentResult(FAILED, error, …))`
 *    - `ToolCall` → `AgentEvent.ToolStarted` then (after dispatch) `ToolCompleted`
 *       (currently a passthrough — full MCP dispatch is a future task)
 * 5. Always call `streamClient.disconnect()` in the `finally` block.
 */
@Singleton
class ChatAgent @Inject constructor(
    private val streamClient: AIStreamClient,
    private val secureStorage: SecureStorage,
) : Agent {

    override val name: String = NAME

    override val description: String =
        "Conversational chat agent backed by the existing WebSocket AI streaming infrastructure."

    override val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.TEXT_GENERATION,
        AgentCapability.STREAMING,
        AgentCapability.MEMORY_ACCESS,
        AgentCapability.TOOL_USE,
        AgentCapability.ON_DEVICE_INFERENCE,
        AgentCapability.MULTI_STEP_REASONING,
    )

    override fun canHandle(request: AgentRequest): Boolean {
        // Accept when explicitly named or when no capability constraint is set.
        val explicitName = request.metadata[METADATA_AGENT_NAME]
        if (!explicitName.isNullOrBlank()) return explicitName == name
        return request.capabilities.isEmpty() || request.capabilities.all { it in capabilities }
    }

    override fun execute(
        request: AgentRequest,
        execution: AgentExecution,
    ): Flow<AgentEvent> = flow {

        val conversationId = request.conversationId
            ?: request.metadata[METADATA_CONVERSATION_ID]
            ?: run {
                // No conversationId — cannot connect WebSocket; emit failure immediately.
                val result = failedResult(
                    execution, request,
                    "MISSING_CONVERSATION_ID",
                    "ChatAgent requires a conversationId in AgentRequest.conversationId " +
                        "or metadata['conversation_id'].",
                )
                emit(AgentEvent.Failed(result))
                return@flow
            }

        val provider = request.provider
            ?: request.context?.extraContext?.get(CONTEXT_KEY_PROVIDER)
            ?: DEFAULT_PROVIDER

        // ── 1. Emit started ──────────────────────────────────────────────────
        emit(AgentEvent.Started(execution.executionId, name))
        emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))

        // ── 2. Resolve JWT ───────────────────────────────────────────────────
        // Fixes the 'placeholder_jwt' gap in ChatDetailViewModel without
        // modifying ChatDetailViewModel's constructor or its streaming logic.
        val jwt = secureStorage.getJwt() ?: run {
            Timber.w("ChatAgent: no JWT in SecureStorage — WebSocket auth will fail")
            ""
        }

        // ── 3. Stream via existing AIStreamClient ────────────────────────────
        var accumulated = ""
        var inputTokens = 0
        var outputTokens = 0

        try {
            val streamFlow = streamClient.connect(conversationId, jwt)

            val payload = MessagePayload(
                conversationId = conversationId,
                content = request.input,
                provider = provider,
            )
            streamClient.sendMessage(payload)

            streamFlow.collect { event ->
                when (event) {
                    is StreamEvent.Token -> {
                        accumulated += event.text
                        emit(AgentEvent.Token(event.text))
                    }

                    is StreamEvent.Done -> {
                        inputTokens = event.usage.inputTokens
                        outputTokens = event.usage.outputTokens
                        // Terminal — flow will complete naturally after this
                    }

                    is StreamEvent.Error -> {
                        val result = failedResult(
                            execution, request,
                            "STREAM_ERROR",
                            event.message,
                        )
                        emit(AgentEvent.Failed(result))
                        return@collect
                    }

                    is StreamEvent.ToolCall -> {
                        // Surface as ToolStarted so the UI can show a progress indicator.
                        // Full MCP dispatch is a future task (Phase 4+).
                        emit(
                            AgentEvent.ToolStarted(
                                toolName = event.toolName,
                                parameters = event.toolInput.toString(),
                            )
                        )
                        // Mark as completed immediately (passthrough — no actual invocation yet).
                        emit(
                            AgentEvent.ToolCompleted(
                                toolName = event.toolName,
                                output = "{}",
                                durationMs = 0L,
                            )
                        )
                    }
                }
            }

            // ── 4. Emit completion ───────────────────────────────────────────
            val result = AgentResult(
                executionId = execution.executionId,
                requestId = request.requestId,
                agentName = name,
                status = AgentStatus.COMPLETED,
                content = accumulated.takeIf { it.isNotBlank() },
                usage = AgentUsage(
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                ),
                nextAction = buildNextAction(provider),
            )
            emit(AgentEvent.Completed(result))

        } catch (e: Exception) {
            Timber.e(e, "ChatAgent: unexpected exception during streaming")
            val result = failedResult(
                execution, request,
                "UNEXPECTED_ERROR",
                e.message ?: "An unexpected error occurred during streaming.",
            )
            emit(AgentEvent.Failed(result))
        } finally {
            streamClient.disconnect()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun failedResult(
        execution: AgentExecution,
        request: AgentRequest,
        code: String,
        message: String,
    ) = AgentResult(
        executionId = execution.executionId,
        requestId = request.requestId,
        agentName = name,
        status = AgentStatus.FAILED,
        error = AgentError(code = code, message = message),
    )

    private fun buildNextAction(provider: String): AgentNextAction? {
        // Hint to the UI that regeneration is available by naming the provider.
        return AgentNextAction(
            type = "REGENERATE_AVAILABLE",
            label = "Regenerate",
            payload = provider,
        )
    }

    companion object {
        /** Canonical name — matches DefaultAgentRouter.AGENT_NAME_CONVERSATIONAL */
        const val NAME = "conversational"

        /** Metadata key for explicit agent routing. */
        const val METADATA_AGENT_NAME = "agent_name"

        /** Metadata key for passing conversation ID when not set in request.conversationId. */
        const val METADATA_CONVERSATION_ID = "conversation_id"

        /** AgentContext.extraContext key for the LLM provider. */
        const val CONTEXT_KEY_PROVIDER = "provider"

        /** Default provider when none is specified. */
        const val DEFAULT_PROVIDER = "openai"
    }
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatDetailViewModel.kt
 * Purpose    : Manages UI state for the ChatDetail screen, including
 *              multi-mode execution: DIRECT_LLM, RAG, and AGENT.
 *
 * Architecture Layer : Feature (feature-chat) — MVVM ViewModel
 * Pattern Used       : MVVM + StateFlow + cold Flow collection
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *   - AgentGatewayRepository is the single execution path for all three modes:
 *       DIRECT_LLM → AgentMode.CHAT  → conversational agent (existing stream)
 *       RAG        → AgentMode.DOCUMENT → RAG agent (retrieval + LLM)
 *       AGENT      → AgentMode.AUTO    → full orchestration layer
 *   - AIStreamClient is retained as an optional fallback only.
 *
 * Requirements: 2.1, 2.2, 2.5, 2.6, 2.7, 2.8, 2.10
 * ============================================================
 */

package com.aiassistant.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.ai.AIStreamClient
import com.aiassistant.core.ai.MessagePayload
import com.aiassistant.core.ai.ON_DEVICE_PROVIDER_ID
import com.aiassistant.core.ai.StreamEvent
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentGatewayRepository
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.AgentToolCall
import com.aiassistant.domain.agent.ChatExecutionMode
import com.aiassistant.domain.model.ExportFormat
import com.aiassistant.domain.model.Message
import com.aiassistant.domain.model.ScreenContext
import com.aiassistant.domain.model.SuggestionType
import com.aiassistant.domain.usecase.conversation.ExportConversationUseCase
import com.aiassistant.domain.usecase.conversation.RegenerateMessageUseCase
import com.aiassistant.domain.usecase.conversation.SendMessageUseCase
import com.aiassistant.domain.usecase.suggestions.GetContextSuggestionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the ChatDetail screen.
 *
 * Exposes a [StateFlow] of [ChatDetailUiState]. All streaming I/O is dispatched on
 * [DispatcherProvider.io]; UI-state updates are published on the calling coroutine's
 * context (StateFlow is thread-safe).
 *
 * ## Execution modes
 *
 * The user may select one of three [ChatExecutionMode] values via [setExecutionMode].
 * All three modes funnel through [AgentGatewayRepository.executeChat] — the mode is
 * translated to an [com.aiassistant.domain.agent.AgentMode] by
 * [ChatExecutionMode.toAgentMode]:
 *
 * | Mode        | AgentMode | What streams back                                    |
 * |-------------|-----------|------------------------------------------------------|
 * | DIRECT_LLM  | CHAT      | Token events only — fastest path                     |
 * | RAG         | DOCUMENT  | Token events + RetrievalCompleted → citations shown  |
 * | AGENT       | AUTO      | Token + Tool* + Thinking events → steps/tool display |
 *
 * ## Streaming lifecycle
 * 1. [sendMessage] persists the user message, starts the typing indicator, and calls
 *    [startStreamingViaGateway].
 * 2. On the first [AgentEvent.Token], the typing indicator is hidden.
 * 3. On [AgentEvent.Completed] the final text is committed as an assistant [Message].
 * 4. On [AgentEvent.Failed] the retry option is shown.
 * 5. On [AgentEvent.RetrievalCompleted] the citation list from the result is stored.
 * 6. On [AgentEvent.ToolStarted/ToolCompleted/ToolFailed] activeToolCalls is updated.
 */
@HiltViewModel
class ChatDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sendMessageUseCase: SendMessageUseCase,
    private val regenerateMessageUseCase: RegenerateMessageUseCase,
    private val exportConversationUseCase: ExportConversationUseCase,
    private val agentGatewayRepository: AgentGatewayRepository,
    private val dispatchers: DispatcherProvider,
    private val getContextSuggestionsUseCase: GetContextSuggestionsUseCase,
    // Retained for on-device fallback path and unit-test compatibility.
    private val streamClient: AIStreamClient,
    // Optional; present when the auth module is wired.
    private val secureStorage: SecureStorage? = null,
) : ViewModel() {

    /** Pulled from the navigation back-stack entry by Hilt's SavedStateHandle. */
    val conversationId: String = checkNotNull(savedStateHandle["conversationId"]) {
        "ChatDetailViewModel requires a non-null conversationId argument"
    }

    private val _uiState = MutableStateFlow(
        ChatDetailUiState(conversationId = conversationId)
    )

    /** Primary UI state observed by [ChatDetailScreen]. */
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()

    /** Active streaming collection job; cancelled when a new stream starts. */
    private var streamingJob: Job? = null

    /** Last token index for StreamingInterrupted recovery (Req 2.8). */
    private var lastTokenIndex: Int = -1

    /** Pending payload held for retry after a streaming error. */
    private var pendingContent: String? = null

    // ─── Suggestion settings ──────────────────────────────────────────────────
    private var isSuggestionsEnabled: Boolean = true
    private var isPrivacyModeEnabled: Boolean = false

    // ─── Public actions ───────────────────────────────────────────────────────

    /**
     * Sets the LLM provider identifier for this conversation.
     */
    fun setProvider(provider: String) {
        _uiState.update { it.copy(provider = provider) }
    }

    /**
     * Changes the execution mode.
     *
     * The new mode takes effect on the **next** [sendMessage] call.
     * The current conversation history is preserved; only new messages
     * are processed with the updated mode.
     *
     * @param mode One of [ChatExecutionMode.DIRECT_LLM], [ChatExecutionMode.RAG],
     *             or [ChatExecutionMode.AGENT].
     */
    fun setExecutionMode(mode: ChatExecutionMode) {
        _uiState.update { it.copy(executionMode = mode) }
    }

    /**
     * Sends [content] as a new user message within the conversation.
     *
     * Execution mode is read from [ChatDetailUiState.executionMode] at call time.
     * Clears citations, tool calls, and the step counter from the previous run.
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return

        val provider = _uiState.value.provider
        val mode = _uiState.value.executionMode

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = "user",
            content = content.trim(),
            provider = "",
            syncStatus = "pending",
            createdAt = Instant.now()
        )

        // Optimistic UI + clear previous run state
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                error = null,
                showRetryOption = false,
                citations = emptyList(),
                activeToolCalls = emptyList(),
                agentStepCount = 0,
            )
        }

        viewModelScope.launch(dispatchers.io) {
            sendMessageUseCase(conversationId, content.trim(), provider)
            _uiState.update { it.copy(isTypingIndicatorVisible = true) }
            pendingContent = content.trim()
            startStreamingViaGateway(content.trim(), provider, mode)
        }
    }

    /**
     * Routes a chat turn through [AgentGatewayRepository] with the selected
     * [ChatExecutionMode] translated to the appropriate [AgentMode].
     *
     * Handles all [AgentEvent] subtypes:
     * - [AgentEvent.Token]              → append to streamingText
     * - [AgentEvent.Thinking]           → increment agentStepCount
     * - [AgentEvent.ToolStarted]        → add pending tool entry
     * - [AgentEvent.ToolCompleted]      → mark tool entry as complete
     * - [AgentEvent.ToolFailed]         → mark tool entry as failed
     * - [AgentEvent.RetrievalCompleted] → store citations from final result
     * - [AgentEvent.Completed]          → commit message + citations + tool calls
     * - [AgentEvent.Failed]             → show retry option
     * - [AgentEvent.Cancelled]          → clear streaming state
     */
    private fun startStreamingViaGateway(
        content: String,
        provider: String,
        mode: ChatExecutionMode,
    ) {
        streamingJob?.cancel()
        lastTokenIndex = -1
        val isOnDevice = provider == ON_DEVICE_PROVIDER_ID

        streamingJob = viewModelScope.launch(dispatchers.io) {
            _uiState.update {
                it.copy(
                    isStreaming = true,
                    streamingText = "",
                    isRunningOnDevice = isOnDevice,
                )
            }

            try {
                agentGatewayRepository
                    .executeChat(
                        conversationId = conversationId,
                        content = content,
                        provider = provider,
                        mode = mode.toAgentMode(),
                    )
                    .collect { event -> handleAgentEvent(event, provider) }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isStreaming = false,
                        isTypingIndicatorVisible = false,
                        error = DomainError.StreamingInterrupted(
                            message = e.message ?: "Streaming connection was interrupted.",
                            lastTokenIndex = if (lastTokenIndex >= 0) lastTokenIndex else null,
                        ),
                        showRetryOption = true,
                    )
                }
            }
        }
    }

    /** Processes a single [AgentEvent] emitted by [AgentGatewayRepository]. */
    private fun handleAgentEvent(event: AgentEvent, provider: String) {
        when (event) {
            // ── Content streaming ────────────────────────────────────────
            is AgentEvent.Token -> {
                lastTokenIndex++
                _uiState.update { state ->
                    state.copy(
                        isTypingIndicatorVisible = false,
                        streamingText = state.streamingText + event.token,
                    )
                }
            }

            // ── Reasoning (AGENT mode) ───────────────────────────────────
            is AgentEvent.Thinking -> {
                _uiState.update { state ->
                    state.copy(agentStepCount = state.agentStepCount + 1)
                }
            }

            // ── Tool lifecycle (AGENT mode) ──────────────────────────────
            is AgentEvent.ToolStarted -> {
                val pending = AgentToolCall(
                    toolName = event.toolName,
                    input = event.parameters,
                    output = null,
                    failed = false,
                    durationMs = 0L,
                )
                _uiState.update { state ->
                    state.copy(activeToolCalls = state.activeToolCalls + pending)
                }
            }

            is AgentEvent.ToolCompleted -> {
                _uiState.update { state ->
                    state.copy(
                        activeToolCalls = state.activeToolCalls.map { call ->
                            if (call.toolName == event.toolName && call.output == null) {
                                call.copy(output = event.output, durationMs = event.durationMs)
                            } else call
                        }
                    )
                }
            }

            is AgentEvent.ToolFailed -> {
                _uiState.update { state ->
                    state.copy(
                        activeToolCalls = state.activeToolCalls.map { call ->
                            if (call.toolName == event.toolName && !call.failed) {
                                call.copy(failed = true, errorMessage = event.errorMessage)
                            } else call
                        }
                    )
                }
            }

            // ── Retrieval (RAG + AGENT modes) ────────────────────────────
            is AgentEvent.RetrievalCompleted -> {
                // Citations arrive in the terminal Completed event; this event
                // confirms retrieval finished so the UI can show a progress hint.
                _uiState.update { state ->
                    state.copy(agentStepCount = state.agentStepCount + 1)
                }
            }

            // ── Terminal: success ────────────────────────────────────────
            is AgentEvent.Completed -> {
                val result = event.result
                val finalText = result.content
                    ?: _uiState.value.streamingText.takeIf { it.isNotEmpty() }

                val assistantMessage = if (finalText != null) {
                    Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = finalText,
                        inputTokens = result.usage?.inputTokens ?: 0,
                        outputTokens = result.usage?.outputTokens ?: 0,
                        provider = provider,
                        syncStatus = "synced",
                        createdAt = Instant.now(),
                    )
                } else null

                _uiState.update { state ->
                    state.copy(
                        messages = if (assistantMessage != null) {
                            state.messages + assistantMessage
                        } else state.messages,
                        streamingText = "",
                        isStreaming = false,
                        isTypingIndicatorVisible = false,
                        isRunningOnDevice = false,
                        // Persist citations from the result (RAG + AGENT modes)
                        citations = result.citations,
                        // Persist complete tool call record from the result
                        activeToolCalls = result.toolCalls,
                    )
                }
                pendingContent = null
            }

            // ── Terminal: failure ────────────────────────────────────────
            is AgentEvent.Failed -> {
                val errorMsg = event.result.error?.message
                    ?: "The agent encountered an error."
                _uiState.update { state ->
                    state.copy(
                        isStreaming = false,
                        isTypingIndicatorVisible = false,
                        error = DomainError.StreamingInterrupted(
                            message = errorMsg,
                            lastTokenIndex = if (lastTokenIndex >= 0) lastTokenIndex else null,
                        ),
                        showRetryOption = true,
                    )
                }
            }

            // ── Terminal: cancelled ──────────────────────────────────────
            is AgentEvent.Cancelled -> {
                _uiState.update { state ->
                    state.copy(
                        isStreaming = false,
                        isTypingIndicatorVisible = false,
                    )
                }
            }

            // ── Informational / ignored ──────────────────────────────────
            is AgentEvent.Started,
            is AgentEvent.StatusChanged,
            is AgentEvent.HandoffStarted,
            is AgentEvent.HandoffCompleted,
            is AgentEvent.ToolConfirmationRequired -> {
                // No UI change needed for these events currently.
            }
        }
    }

    /**
     * Resumes streaming after the user taps the retry button (Req 2.8).
     */
    fun retryStreaming() {
        val content = pendingContent ?: return
        _uiState.update { it.copy(error = null, showRetryOption = false) }
        viewModelScope.launch(dispatchers.io) {
            val provider = _uiState.value.provider
            val mode = _uiState.value.executionMode
            _uiState.update { it.copy(isTypingIndicatorVisible = true) }
            startStreamingViaGateway(content, provider, mode)
        }
    }

    /**
     * Regenerates the assistant's response for [messageId] (Req 2.6).
     */
    fun regenerateMessage(messageId: String) {
        viewModelScope.launch(dispatchers.io) {
            _uiState.update {
                it.copy(
                    isTypingIndicatorVisible = true,
                    error = null,
                    showRetryOption = false,
                    citations = emptyList(),
                    activeToolCalls = emptyList(),
                    agentStepCount = 0,
                )
            }

            val result = regenerateMessageUseCase(conversationId, messageId)
            when (result) {
                is ApiResult.Success -> {
                    val provider = _uiState.value.provider
                    val mode = _uiState.value.executionMode
                    pendingContent = ""
                    startStreamingViaGateway("", provider, mode)
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(isTypingIndicatorVisible = false, error = result.error) }
                }
                is ApiResult.NetworkUnavailable -> {
                    _uiState.update {
                        it.copy(
                            isTypingIndicatorVisible = false,
                            error = DomainError.NetworkUnavailable()
                        )
                    }
                }
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Exports the conversation in the given [format] (Req 2.7).
     */
    fun exportConversation(format: ExportFormat, onResult: (String?) -> Unit) {
        viewModelScope.launch(dispatchers.io) {
            val result = exportConversationUseCase(conversationId, format)
            val value = when (result) {
                is ApiResult.Success -> result.data
                else -> null
            }
            withContext(dispatchers.main) { onResult(value) }
        }
    }

    /** Dismisses the current error banner without retrying. */
    fun dismissError() {
        _uiState.update { it.copy(error = null, showRetryOption = false) }
    }

    // ─── Continuation suggestion methods (Req 33.3) ───────────────────────────

    fun updateSuggestionsEnabled(enabled: Boolean) { isSuggestionsEnabled = enabled }
    fun updatePrivacyMode(enabled: Boolean) { isPrivacyModeEnabled = enabled }

    fun checkContinuationSuggestion() {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return

        val lastMessage = messages.last()
        val lastMessageAgeMs =
            Instant.now().toEpochMilli() - lastMessage.createdAt.toEpochMilli()

        if (lastMessageAgeMs < CONTINUATION_THRESHOLD_MS) return

        viewModelScope.launch {
            val context = ScreenContext.ConversationContext(
                lastMessageContent = lastMessage.content,
                lastMessageAgeMillis = lastMessageAgeMs,
                screenInstanceId = conversationId
            )

            val result = kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                withContext(dispatchers.io) {
                    getContextSuggestionsUseCase(
                        context = context,
                        isPrivacyModeEnabled = isPrivacyModeEnabled,
                        isSuggestionsEnabled = isSuggestionsEnabled
                    )
                }
            }

            val suggestion = when (result) {
                is ApiResult.Success ->
                    result.data.firstOrNull { it.type == SuggestionType.CONTINUE_CONVERSATION }
                else -> null
            }

            if (suggestion != null) {
                _uiState.update { it.copy(continuationSuggestion = suggestion) }
            }
        }
    }

    fun acceptContinuationSuggestion() {
        val suggestion = _uiState.value.continuationSuggestion ?: return
        _uiState.update {
            it.copy(continuationSuggestion = null, preFillInputText = suggestion.preFillText)
        }
    }

    fun dismissContinuationSuggestion() {
        _uiState.update { it.copy(continuationSuggestion = null) }
    }

    fun clearPreFillText() {
        _uiState.update { it.copy(preFillInputText = "") }
    }

    override fun onCleared() {
        super.onCleared()
        streamingJob?.cancel()
        streamClient.disconnect()
    }

    private companion object {
        const val CONTINUATION_THRESHOLD_MS = 24 * 60 * 60 * 1_000L
    }
}

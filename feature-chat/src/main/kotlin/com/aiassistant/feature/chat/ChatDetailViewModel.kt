/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatDetailViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the ChatDetail feature
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : MVVM ViewModel
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatDetailViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the ChatDetail feature
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : MVVM ViewModel
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * ChatDetailViewModel.kt
 *
 * Purpose: Manages all UI state for the ChatDetail screen. Connects the AIStreamClient
 *          WebSocket, processes StreamEvent tokens incrementally, handles typing indicator,
 *          regeneration, and streaming error recovery.
 * Architecture: feature-chat â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain use cases, AIStreamClient (core-ai), DispatcherProvider (core-common)
 *
 * Requirements: 2.1, 2.2, 2.5, 2.6, 2.7, 2.8, 2.10
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
 * [DispatcherProvider.io]; UI state updates are published on the calling coroutine's
 * context (StateFlow is thread-safe).
 *
 * Streaming lifecycle:
 * 1. [sendMessage] persists the user message, starts the typing indicator, connects
 *    the AIStreamClient, and sends the payload.
 * 2. On the first [StreamEvent.Token], the typing indicator is hidden; tokens are
 *    appended to [ChatDetailUiState.streamingText].
 * 3. On [StreamEvent.Done] the accumulated text is committed to [ChatDetailUiState.messages]
 *    as a new assistant Message and the streaming state is cleared.
 * 4. On [StreamEvent.Error] the [ChatDetailUiState.showRetryOption] flag is set; the
 *    stream is not resumed until the user taps [retryStreaming].
 *
 * The JWT is currently hard-coded to a placeholder; the auth module provides the real
 * token through a shared [SecureStorage] dependency in a future integration task.
 */
@HiltViewModel
class ChatDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sendMessageUseCase: SendMessageUseCase,
    private val regenerateMessageUseCase: RegenerateMessageUseCase,
    private val exportConversationUseCase: ExportConversationUseCase,
    private val streamClient: AIStreamClient,
    private val dispatchers: DispatcherProvider,
    private val getContextSuggestionsUseCase: GetContextSuggestionsUseCase
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

    /** Token index tracker for StreamingInterrupted recovery (Req 2.8). */
    private var lastTokenIndex: Int = -1

    /** Pending message content held for retry after a StreamingInterrupted error. */
    private var pendingMessagePayload: MessagePayload? = null

    // ─── Suggestion settings (updated by Settings screen) ────────────────────
    private var isSuggestionsEnabled: Boolean = true
    private var isPrivacyModeEnabled: Boolean = false

    // â”€â”€â”€ Public actions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Sets the LLM provider identifier for this conversation.
     */
    fun setProvider(provider: String) {
        _uiState.update { it.copy(provider = provider) }
    }

    /**
     * Sends [content] as a new user message within the conversation.
     *
     * - Validates via [SendMessageUseCase].
     * - Appends the user message optimistically to [ChatDetailUiState.messages].
     * - Shows typing indicator (Req 2.10).
     * - Starts streaming from the AIStreamClient (Req 2.2).
     *
     * @param content The user's message text. Must not be blank.
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) return

        val provider = _uiState.value.provider

        // Optimistically append user message to the list immediately
        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = "user",
            content = content.trim(),
            provider = "",
            syncStatus = "pending",
            createdAt = Instant.now()
        )
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                error = null,
                showRetryOption = false
            )
        }

        viewModelScope.launch(dispatchers.io) {
            // Persist via use case (non-blocking; optimistic UI already shown)
            sendMessageUseCase(conversationId, content.trim(), provider)

            // Show typing indicator before the stream starts (Req 2.10)
            _uiState.update { it.copy(isTypingIndicatorVisible = true) }

            val payload = MessagePayload(
                conversationId = conversationId,
                content = content.trim(),
                provider = provider
            )
            pendingMessagePayload = payload
            startStreaming(payload)
        }
    }

    /**
     * Connects the AIStreamClient and processes [StreamEvent] values.
     *
     * Called internally by [sendMessage] and [retryStreaming].
     */
    private fun startStreaming(payload: MessagePayload) {
        streamingJob?.cancel()
        lastTokenIndex = -1

        // Placeholder JWT â€” real token comes from SecureStorage in auth module
        val jwt = "placeholder_jwt"

        // Determine whether this request uses on-device inference (Requirement 31.3)
        val isOnDevice = payload.provider == ON_DEVICE_PROVIDER_ID

        streamingJob = viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isStreaming = true, streamingText = "", isRunningOnDevice = isOnDevice) }

            try {
                val flow = streamClient.connect(conversationId, jwt)

                // Send the message payload after connection is established
                streamClient.sendMessage(payload)

                flow.collect { event ->
                    when (event) {
                        is StreamEvent.Token -> {
                            lastTokenIndex++
                            _uiState.update { state ->
                                state.copy(
                                    // Hide typing indicator on first token (Req 2.10)
                                    isTypingIndicatorVisible = false,
                                    streamingText = state.streamingText + event.text
                                )
                            }
                        }

                        is StreamEvent.Done -> {
                            // Commit accumulated streaming text as a persisted assistant message
                            val completedText = _uiState.value.streamingText
                            if (completedText.isNotEmpty()) {
                                val assistantMessage = Message(
                                    id = UUID.randomUUID().toString(),
                                    conversationId = conversationId,
                                    role = "assistant",
                                    content = completedText,
                                    inputTokens = event.usage.inputTokens,
                                    outputTokens = event.usage.outputTokens,
                                    provider = _uiState.value.provider,
                                    syncStatus = "synced",
                                    createdAt = Instant.now()
                                )
                                _uiState.update { state ->
                                    state.copy(
                                        messages = state.messages + assistantMessage,
                                        streamingText = "",
                                        isStreaming = false,
                                        isTypingIndicatorVisible = false,
                                        isRunningOnDevice = false
                                    )
                                }
                            } else {
                                _uiState.update {
                                    it.copy(
                                        isStreaming = false,
                                        isTypingIndicatorVisible = false,
                                        isRunningOnDevice = false
                                    )
                                }
                            }
                            pendingMessagePayload = null
                        }

                        is StreamEvent.Error -> {
                            // Streaming interrupted â€” show retry option, do not auto-reconnect (Req 2.8)
                            _uiState.update { state ->
                                state.copy(
                                    isStreaming = false,
                                    isTypingIndicatorVisible = false,
                                    error = DomainError.StreamingInterrupted(
                                        message = event.message,
                                        lastTokenIndex = if (lastTokenIndex >= 0) lastTokenIndex else null
                                    ),
                                    showRetryOption = true
                                )
                            }
                        }

                        is StreamEvent.ToolCall -> {
                            // Tool calls are handled transparently; tokens continue after the tool completes
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isStreaming = false,
                        isTypingIndicatorVisible = false,
                        error = DomainError.StreamingInterrupted(
                            message = e.message ?: "Streaming connection was interrupted.",
                            lastTokenIndex = if (lastTokenIndex >= 0) lastTokenIndex else null
                        ),
                        showRetryOption = true
                    )
                }
            }
        }
    }

    /**
     * Resumes streaming after the user taps the retry button.
     *
     * Clears the error / retry state and re-opens the WebSocket (Req 2.8).
     * No-op when there is no pending payload to retry.
     */
    fun retryStreaming() {
        val payload = pendingMessagePayload ?: return
        _uiState.update { it.copy(error = null, showRetryOption = false) }
        viewModelScope.launch(dispatchers.io) {
            startStreaming(payload)
        }
    }

    /**
     * Regenerates the assistant's response for the message identified by [messageId].
     *
     * The new response is appended as an alternative to the existing message (Req 2.6).
     * Starts a new streaming session after the regeneration request is accepted.
     *
     * @param messageId The ID of the assistant message to regenerate.
     */
    fun regenerateMessage(messageId: String) {
        viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isTypingIndicatorVisible = true, error = null, showRetryOption = false) }

            val result = regenerateMessageUseCase(conversationId, messageId)
            when (result) {
                is ApiResult.Success -> {
                    // The regenerated message is streamed; start the stream
                    val provider = _uiState.value.provider
                    val payload = MessagePayload(
                        conversationId = conversationId,
                        content = "", // context is inferred server-side for regeneration
                        provider = provider
                    )
                    pendingMessagePayload = payload
                    startStreaming(payload)
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
     * Exports the conversation in the given [format].
     *
     * @param format [ExportFormat.MARKDOWN] or [ExportFormat.PDF].
     * @param onResult Callback invoked with the exported content string or file path,
     *                 or `null` on failure.
     */
    fun exportConversation(format: ExportFormat, onResult: (String?) -> Unit) {
        viewModelScope.launch(dispatchers.io) {
            val result = exportConversationUseCase(conversationId, format)
            val value = when (result) {
                is ApiResult.Success -> result.data
                else -> null
            }
            withContext(dispatchers.main) {
                onResult(value)
            }
        }
    }

    /**
     * Dismisses the current error banner without retrying.
     */
    fun dismissError() {
        _uiState.update { it.copy(error = null, showRetryOption = false) }
    }

    // ─── Continuation suggestion methods (Requirement 33.3) ──────────────────

    /**
     * Updates the enabled state for global context suggestions (Requirement 33.8).
     *
     * @param enabled `false` when the user has disabled suggestions globally.
     */
    fun updateSuggestionsEnabled(enabled: Boolean) {
        isSuggestionsEnabled = enabled
    }

    /**
     * Updates the privacy mode flag (Requirement 33.7).
     *
     * @param enabled `true` when privacy mode is active.
     */
    fun updatePrivacyMode(enabled: Boolean) {
        isPrivacyModeEnabled = enabled
    }

    /**
     * Checks whether the last message in this conversation is older than 24 hours and,
     * if so, requests a "Continue this conversation" suggestion (Requirement 33.3).
     *
     * - Uses a 3-second timeout; silently leaves [ChatDetailUiState.continuationSuggestion]
     *   null on timeout or if no messages exist.
     * - No loading indicator is shown while the request is in-flight.
     * - No-op when privacy mode is enabled or suggestions are globally disabled.
     *
     * Call this after the initial message list is loaded.
     */
    fun checkContinuationSuggestion() {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return

        val lastMessage = messages.last()
        val lastMessageAgeMs = Instant.now().toEpochMilli() - lastMessage.createdAt.toEpochMilli()

        // Only suggest continuation when the last message is >24 hours old (Req 33.3)
        if (lastMessageAgeMs < CONTINUATION_THRESHOLD_MS) return

        viewModelScope.launch {
            val context = ScreenContext.ConversationContext(
                lastMessageContent = lastMessage.content,
                lastMessageAgeMillis = lastMessageAgeMs,
                screenInstanceId = conversationId
            )

            // 3-second timeout — no loading indicator shown
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
                is ApiResult.Success -> result.data.firstOrNull {
                    it.type == SuggestionType.CONTINUE_CONVERSATION
                }
                else -> null // timeout or error — leave suggestion null
            }

            if (suggestion != null) {
                _uiState.update { it.copy(continuationSuggestion = suggestion) }
            }
        }
    }

    /**
     * Accepts the continuation suggestion by pre-filling the input field with its
     * [preFillText] and clearing the chip (Requirement 33.3).
     */
    fun acceptContinuationSuggestion() {
        val suggestion = _uiState.value.continuationSuggestion ?: return
        _uiState.update {
            it.copy(
                continuationSuggestion = null,
                preFillInputText = suggestion.preFillText
            )
        }
    }

    /**
     * Dismisses the continuation suggestion chip without acting on it (Requirement 33.5).
     *
     * The dismissal is session-scoped via the ViewModel lifecycle.
     */
    fun dismissContinuationSuggestion() {
        _uiState.update { it.copy(continuationSuggestion = null) }
    }

    /**
     * Clears the pre-fill text after it has been consumed by the input field.
     */
    fun clearPreFillText() {
        _uiState.update { it.copy(preFillInputText = "") }
    }

    override fun onCleared() {
        super.onCleared()
        streamingJob?.cancel()
        streamClient.disconnect()
    }

    private companion object {
        /** Threshold in milliseconds after which a stale conversation gets a continuation chip (24 hours). */
        const val CONTINUATION_THRESHOLD_MS = 24 * 60 * 60 * 1_000L
    }
}

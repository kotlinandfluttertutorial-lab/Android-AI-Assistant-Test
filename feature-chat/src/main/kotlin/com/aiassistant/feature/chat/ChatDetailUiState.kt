/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatDetailUiState.kt
 * Purpose    : ChatDetailUiState — feature-chat module component
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : UI State Data Class
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
 * File       : ChatDetailUiState.kt
 * Purpose    : ChatDetailUiState — feature-chat module component
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : UI State Data Class
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
 * ChatDetailUiState.kt
 *
 * Purpose: Data class representing the complete observable UI state for the ChatDetail screen.
 * Architecture: feature-chat â€” MVVM presentation layer.
 * Dependencies: domain (Message, DomainError)
 *
 * Requirements: 2.1, 2.2, 2.5, 2.6, 2.7, 2.8, 2.10
 */
package com.aiassistant.feature.chat

import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Message

/**
 * Complete UI state for the ChatDetail screen.
 *
 * [ChatDetailViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this class.
 *
 * @param messages                  The persisted list of messages in this conversation,
 *                                  ordered oldest-first.
 * @param streamingText             Accumulates incremental token text while a response is
 *                                  being streamed. Empty when not streaming.
 * @param isStreaming               True while the WebSocket connection is active and
 *                                  tokens may still arrive.
 * @param isTypingIndicatorVisible  True from the moment a message is sent until the first
 *                                  token of the Streaming_Response is received (Req 2.10).
 * @param error                     Non-null when the last operation produced a [DomainError].
 * @param showRetryOption           True when a [DomainError.StreamingInterrupted] error
 *                                  occurred; the UI shows a retry button and waits for the
 *                                  user to tap before reconnecting (Req 2.8).
 * @param isLoading                 True while the initial message history is loading.
 * @param conversationId            The conversation this state belongs to.
 * @param provider                  LLM provider identifier for this conversation.
 * @param isRunningOnDevice         True while on-device inference is active for this
 *                                  conversation. Drives the persistent "Running on device"
 *                                  indicator (Requirement 31.3).
 * @param continuationSuggestion    The "Continue this conversation" chip suggestion shown
 *                                  when the last message is >24 hours old (Requirement 33.3).
 * @param preFillInputText          Text to pre-fill into the message input field when a
 *                                  continuation suggestion is accepted (Requirement 33.3).
 */
data class ChatDetailUiState(
    val messages: List<Message> = emptyList(),
    val streamingText: String = "",
    val isStreaming: Boolean = false,
    val isTypingIndicatorVisible: Boolean = false,
    val error: DomainError? = null,
    val showRetryOption: Boolean = false,
    val isLoading: Boolean = true,
    val conversationId: String = "",
    val provider: String = "openai",
    val isRunningOnDevice: Boolean = false,
    val continuationSuggestion: com.aiassistant.domain.model.ContextSuggestion? = null,
    val preFillInputText: String = ""
)

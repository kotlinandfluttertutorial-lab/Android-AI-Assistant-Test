/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatUiModels.kt
 * Purpose    : @Stable / @Immutable UI model wrappers for the chat feature
 *              (Phases 15.2, 15.4).
 *
 *              Marking these classes @Stable / @Immutable tells the Compose
 *              compiler that instances won't change in ways that invalidate
 *              composition without notifying the snapshot system — preventing
 *              unnecessary recompositions in the chat message LazyColumn.
 *
 *              Key performance decisions:
 *              - MessageUiModel wraps domain.Message with a stable key (id)
 *                and derives display properties once at mapping time.
 *              - StreamingState is isolated so only the streaming composable
 *                recomposes when new tokens arrive — not the full message list.
 *              - ChatDetailPerfState separates the stable message list from the
 *                volatile streaming text, preventing list recomposition while
 *                tokens stream in.
 *
 * Requirements : 15.2, 15.4
 * ============================================================
 */
package com.aiassistant.feature.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.aiassistant.core.ui.components.AiMode
import com.aiassistant.core.ui.components.toAiMode
import com.aiassistant.domain.model.Message

/**
 * Immutable UI representation of a single chat message.
 *
 * Derived once at mapping time; never mutates after construction.
 * Using @Immutable lets the Compose compiler skip recomposition of
 * MessageBubble composables when the parent state changes but this
 * item has not.
 *
 * @param id            Stable unique key used by LazyColumn.
 * @param text          Rendered message text.
 * @param isUser        True for user messages, false for assistant.
 * @param aiMode        AI mode that produced this message (null for user).
 * @param isFirstInTurn True when this is the first assistant message in a turn
 *                      (controls AiModeIndicator visibility).
 * @param timestamp     Formatted display timestamp.
 * @param isError       True when this message represents an error state.
 */
@Immutable
data class MessageUiModel(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val aiMode: AiMode?,
    val isFirstInTurn: Boolean,
    val timestamp: String,
    val isError: Boolean = false
)

/**
 * Stable holder for the volatile streaming state.
 *
 * By keeping streaming text in a separate @Stable class (not inside the
 * @Immutable message list), only the StreamingMessage composable recomposes
 * as tokens arrive — the LazyColumn items remain stable.
 */
@Stable
data class StreamingState(
    val isStreaming: Boolean = false,
    val partialText: String = "",
    val isTypingIndicatorVisible: Boolean = false
)

/**
 * Maps a [Message] domain model to [MessageUiModel].
 *
 * @param previousMessage The message immediately before this one in the list,
 *                        used to detect turn boundaries for AiModeIndicator.
 */
fun Message.toUiModel(previousMessage: Message? = null): MessageUiModel {
    val isAssistant = !isFromUser
    val isFirstInTurn = isAssistant &&
        (previousMessage == null || previousMessage.isFromUser)

    return MessageUiModel(
        id            = id,
        text          = content,
        isUser        = isFromUser,
        aiMode        = if (isAssistant) provider?.toAiMode() else null,
        isFirstInTurn = isFirstInTurn,
        timestamp     = timestamp?.toString() ?: "",
        isError       = false
    )
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatDetailScreen.kt
 * Purpose    : Compose UI screen for the ChatDetail feature
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : Jetpack Compose Screen
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
 * File       : ChatDetailScreen.kt
 * Purpose    : Compose UI screen for the ChatDetail feature
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : Jetpack Compose Screen
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
 * ChatDetailScreen.kt
 *
 * Purpose: Jetpack Compose screen for an active chat conversation. Displays the message
 *          history, streams AI responses token-by-token, shows a typing indicator while
 *          waiting for the first token, renders assistant messages with Markdown, and
 *          handles regeneration, copy/share/export, and streaming retry.
 * Architecture: feature-chat â€” Compose UI layer; state driven by [ChatDetailViewModel].
 * Dependencies: Compose Material 3, core-ui (MarkdownText, ChatBubble, ChatBubbleRole,
 *               ErrorBanner, LoadingIndicator), Hilt Navigation Compose
 *
 * Requirements: 2.1, 2.2, 2.5, 2.6, 2.7, 2.8, 2.10
 */
package com.aiassistant.feature.chat

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.components.ChatBubble
import com.aiassistant.core.ui.components.ChatBubbleRole
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.MarkdownText
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.ExportFormat
import com.aiassistant.domain.model.Message
import java.time.Instant

// â”€â”€â”€ Screen entry point â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Stateful entry point for the chat detail screen. Collects state from
 * [ChatDetailViewModel] and delegates rendering to the stateless overload.
 *
 * @param viewModel   The Hilt-provided [ChatDetailViewModel].
 * @param onNavigateUp Called when the user taps the back arrow.
 */
@Composable
fun ChatDetailScreen(viewModel: ChatDetailViewModel, onNavigateUp: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    ChatDetailScreenContent(
        uiState = uiState,
        onSendMessage = viewModel::sendMessage,
        onRetryStreaming = viewModel::retryStreaming,
        onRegenerateMessage = viewModel::regenerateMessage,
        onDismissError = viewModel::dismissError,
        onExportConversation = { format ->
            viewModel.exportConversation(format) { result ->
                result?.let { shareText(context, it) }
            }
        },
        onAcceptContinuationSuggestion = viewModel::acceptContinuationSuggestion,
        onDismissContinuationSuggestion = viewModel::dismissContinuationSuggestion,
        onPreFillConsumed = viewModel::clearPreFillText,
        onNavigateUp = onNavigateUp
    )
}

// â”€â”€â”€ Stateless screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Stateless ChatDetail screen. All state is passed in; side effects communicated via callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatDetailScreenContent(
    uiState: ChatDetailUiState,
    onSendMessage: (String) -> Unit,
    onRetryStreaming: () -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onDismissError: () -> Unit,
    onExportConversation: (ExportFormat) -> Unit,
    onNavigateUp: () -> Unit,
    onAcceptContinuationSuggestion: () -> Unit = {},
    onDismissContinuationSuggestion: () -> Unit = {},
    onPreFillConsumed: () -> Unit = {}
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when messages or streaming text changes
    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        val totalItems = uiState.messages.size +
            (if (uiState.streamingText.isNotEmpty()) 1 else 0) +
            (if (uiState.isTypingIndicatorVisible) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conversation") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics { contentDescription = "Navigate back" }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    ExportMenuButton(onExportConversation = onExportConversation)
                }
            )
        },
        bottomBar = {
            Column {
                // "Continue this conversation" chip (Requirement 33.3)
                // Shown when the last message is >24 hours old. Non-blocking, dismissible.
                if (uiState.continuationSuggestion != null) {
                    ContinuationSuggestionChip(
                        suggestion = uiState.continuationSuggestion,
                        onAccept = onAcceptContinuationSuggestion,
                        onDismiss = onDismissContinuationSuggestion
                    )
                }
                MessageInputBar(
                    isStreaming = uiState.isStreaming,
                    preFillText = uiState.preFillInputText,
                    onPreFillConsumed = onPreFillConsumed,
                    onSendMessage = onSendMessage
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Error banner (non-retry errors)
            if (uiState.error != null && !uiState.showRetryOption) {
                ErrorBanner(
                    message = uiState.error.message,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Retry banner for StreamingInterrupted (Req 2.8)
            if (uiState.showRetryOption) {
                RetryBanner(
                    errorMessage = uiState.error?.message ?: "Streaming was interrupted.",
                    onRetry = onRetryStreaming,
                    onDismiss = onDismissError
                )
            }

            // "Running on device" persistent indicator (Requirement 31.3)
            AnimatedVisibility(
                visible = uiState.isRunningOnDevice,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OnDeviceBanner()
            }

            // Message list
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    horizontal = MaterialTheme.spacing.sm,
                    vertical = MaterialTheme.spacing.sm
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                items(
                    items = uiState.messages,
                    key = { message -> message.id }
                ) { message ->
                    MessageItem(
                        message = message,
                        onRegenerate = { onRegenerateMessage(message.id) }
                    )
                }

                // In-progress streaming response (Req 2.2)
                if (uiState.streamingText.isNotEmpty()) {
                    item(key = "streaming") {
                        StreamingMessageItem(text = uiState.streamingText)
                    }
                }

                // Typing indicator (Req 2.10)
                if (uiState.isTypingIndicatorVisible) {
                    item(key = "typing") {
                        TypingIndicator()
                    }
                }
            }
        }
    }
}

// â”€â”€â”€ Message item â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * A single message row. User messages use [ChatBubble]; assistant messages use
 * [MarkdownText] inside an assistant-styled bubble with action menu (Req 2.5, 2.6, 2.7).
 */
@Composable
private fun MessageItem(message: Message, onRegenerate: () -> Unit) {
    val isUser = message.role == "user"
    if (isUser) {
        ChatBubble(
            text = message.content,
            role = ChatBubbleRole.USER,
            contentDescription = "You: ${message.content}",
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        AssistantMessageItem(
            message = message,
            onRegenerate = onRegenerate
        )
    }
}

/**
 * Assistant message bubble with MarkdownText and action menu (Req 2.5, 2.6, 2.7).
 */
@Composable
private fun AssistantMessageItem(message: Message, onRegenerate: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                end = MaterialTheme.spacing.xxl,
                top = MaterialTheme.spacing.xs,
                bottom = MaterialTheme.spacing.xs
            ),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Assistant: ${message.content}" }
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.spacing.sm)) {
                // Role label
                Text(
                    text = "Assistant",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

                // Markdown rendering (Req 2.5)
                MarkdownText(
                    markdown = message.content,
                    contentDescription = message.content,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Action menu (Req 2.6, 2.7)
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.semantics { contentDescription = "Message actions" }
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Copy as plain text") },
                    leadingIcon = {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        clipboardManager.setText(AnnotatedString(message.content))
                    },
                    modifier = Modifier.semantics { contentDescription = "Copy message as plain text" }
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = {
                        Icon(Icons.Filled.Share, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        shareText(context, message.content)
                    },
                    modifier = Modifier.semantics { contentDescription = "Share message" }
                )
                DropdownMenuItem(
                    text = { Text("Regenerate") },
                    leadingIcon = {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        onRegenerate()
                    },
                    modifier = Modifier.semantics { contentDescription = "Regenerate response" }
                )
            }
        }
    }
}

// â”€â”€â”€ Streaming message item â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * A live streaming assistant bubble that incrementally renders [text] as tokens arrive
 * (Req 2.2). Uses the same surface style as [AssistantMessageItem] for visual consistency.
 */
@Composable
private fun StreamingMessageItem(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                end = MaterialTheme.spacing.xxl,
                top = MaterialTheme.spacing.xs,
                bottom = MaterialTheme.spacing.xs
            ),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Assistant is responding: $text" }
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.spacing.sm)) {
                Text(
                    text = "Assistant",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

                // Render partial Markdown as it streams in (Req 2.5)
                MarkdownText(
                    markdown = text,
                    contentDescription = text,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// â”€â”€â”€ Typing indicator â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Three pulsing dots shown while the typing indicator is active (Req 2.10). Disappears
 * the moment the first token of the streaming response arrives.
 */
@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing_indicator")

    // Stagger each dot by 200 ms so they pulse in sequence
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                end = MaterialTheme.spacing.xxl,
                top = MaterialTheme.spacing.xs,
                bottom = MaterialTheme.spacing.xs
            ),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Assistant is typing" }
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.sm
                ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(dot1Alpha)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(dot2Alpha)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(dot3Alpha)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// â”€â”€â”€ Retry banner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Persistent banner shown while on-device inference is active (Requirement 31.3).
 *
 * Displays a small privacy-themed strip with a lock icon and "Running on device" label
 * so the user knows no network calls are being made. It fades in/out with
 * [AnimatedVisibility] driven by [ChatDetailUiState.isRunningOnDevice].
 *
 * Accessibility: [contentDescription] is set for TalkBack (Requirement 23.1).
 */
@Composable
private fun OnDeviceBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "Running on device: inference is running locally with no network calls"
            },
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.xs
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
            Text(
                text = "Running on device",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * Displayed when a [DomainError.StreamingInterrupted] error occurs. Does not auto-reconnect;
 * waits for the user to tap the retry button (Req 2.8).
 */
@Composable
private fun RetryBanner(errorMessage: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    com.aiassistant.core.ui.components.ErrorBanner(
        message = errorMessage,
        onRetry = onRetry,
        onDismiss = onDismiss,
        contentDescription = "Streaming interrupted: $errorMessage. Tap Retry to reconnect.",
        modifier = Modifier.fillMaxWidth()
    )
}

// --- Continuation suggestion chip ---

/**
 * A dismissible chip offering to continue a stale conversation (Requirement 33.3).
 *
 * Displayed above the message input bar when the last message is >24 hours old.
 * Tapping the chip calls [onAccept] which pre-fills the input with the suggestion preFillText.
 * Tapping the X icon calls [onDismiss] which hides the chip for the session.
 *
 * @param suggestion The ContextSuggestion of type SuggestionType.CONTINUE_CONVERSATION.
 * @param onAccept   Called when the user taps the chip label.
 * @param onDismiss  Called when the user taps the dismiss (X) icon.
 */
@Composable
private fun ContinuationSuggestionChip(
    suggestion: com.aiassistant.domain.model.ContextSuggestion,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "AI suggestion: ${suggestion.displayText}" },
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.sm,
                    vertical = MaterialTheme.spacing.xs
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            androidx.compose.material3.AssistChip(
                onClick = onAccept,
                label = {
                    androidx.compose.material3.Text(
                        text = suggestion.displayText,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription =
                            "Tap to continue conversation: ${suggestion.displayText}"
                    }
            )
            androidx.compose.material3.IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(MaterialTheme.spacing.lg)
                    .semantics {
                        contentDescription = "Dismiss suggestion: ${suggestion.displayText}"
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(MaterialTheme.spacing.md)
                )
            }
        }
    }
}
// â”€â”€â”€ Message input bar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Bottom input bar with a text field and send button. The send button is disabled while
 * [isStreaming] is true to prevent overlapping requests.
 *
 * When [preFillText] is non-empty a [LaunchedEffect] sets it into the text field and
 * then calls [onPreFillConsumed] to clear the state (Requirement 33.3).
 */
@Composable
private fun MessageInputBar(
    isStreaming: Boolean,
    onSendMessage: (String) -> Unit,
    preFillText: String = "",
    onPreFillConsumed: () -> Unit = {}
) {
    var inputText by rememberSaveable { mutableStateOf("") }

    // Apply pre-fill text when it arrives (continuation suggestion accepted)
    LaunchedEffect(preFillText) {
        if (preFillText.isNotEmpty()) {
            inputText = preFillText
            onPreFillConsumed()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.sm,
                    vertical = MaterialTheme.spacing.xs
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Message input" },
                placeholder = { Text("Type a messageâ€¦") },
                maxLines = 5,
                enabled = !isStreaming
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
            IconButton(
                onClick = {
                    val trimmed = inputText.trim()
                    if (trimmed.isNotEmpty()) {
                        onSendMessage(trimmed)
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() && !isStreaming,
                modifier = Modifier.semantics { contentDescription = "Send message" }
            ) {
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = if (inputText.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }
        }
    }
}

// â”€â”€â”€ Export menu button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * TopAppBar action button that reveals an export format picker (Markdown / PDF).
 */
@Composable
private fun ExportMenuButton(onExportConversation: (ExportFormat) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.semantics { contentDescription = "Export conversation" }
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = null
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Export as Markdown") },
                onClick = {
                    menuExpanded = false
                    onExportConversation(ExportFormat.MARKDOWN)
                },
                modifier = Modifier.semantics { contentDescription = "Export as Markdown" }
            )
            DropdownMenuItem(
                text = { Text("Export as PDF") },
                onClick = {
                    menuExpanded = false
                    onExportConversation(ExportFormat.PDF)
                },
                modifier = Modifier.semantics { contentDescription = "Export as PDF" }
            )
        }
    }
}

// â”€â”€â”€ Utilities â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Launches the Android share sheet to share [text] via any installed app.
 */
internal fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share message"))
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "ChatDetail â€“ Loading / empty")
@Composable
private fun ChatDetailEmptyPreview() {
    AppTheme(dynamicColor = false) {
        ChatDetailScreenContent(
            uiState = ChatDetailUiState(isLoading = false),
            onSendMessage = {},
            onRetryStreaming = {},
            onRegenerateMessage = {},
            onDismissError = {},
            onExportConversation = {},
            onNavigateUp = {}
        )
    }
}

@Preview(showBackground = true, name = "ChatDetail â€“ With messages")
@Composable
private fun ChatDetailWithMessagesPreview() {
    val messages = listOf(
        Message(
            id = "1",
            conversationId = "conv1",
            role = "user",
            content = "Can you explain Kotlin coroutines?",
            createdAt = java.time.Instant.now()
        ),
        Message(
            id = "2",
            conversationId = "conv1",
            role = "assistant",
            content = """
                **Kotlin Coroutines** are a concurrency design pattern that you can use on Android to simplify code that executes asynchronously.

                ### Key concepts:
                - **suspend** functions can be paused and resumed
                - **CoroutineScope** defines the lifecycle of coroutines
                - **Dispatchers** control which thread the coroutine runs on

                ```kotlin
                suspend fun fetchData(): String {
                    delay(1000) // non-blocking wait
                    return "data"
                }
                ```
            """.trimIndent(),
            createdAt = java.time.Instant.now()
        )
    )

    AppTheme(dynamicColor = false) {
        ChatDetailScreenContent(
            uiState = ChatDetailUiState(messages = messages, isLoading = false),
            onSendMessage = {},
            onRetryStreaming = {},
            onRegenerateMessage = {},
            onDismissError = {},
            onExportConversation = {},
            onNavigateUp = {}
        )
    }
}

@Preview(showBackground = true, name = "ChatDetail â€“ Typing indicator")
@Composable
private fun ChatDetailTypingPreview() {
    AppTheme(dynamicColor = false) {
        ChatDetailScreenContent(
            uiState = ChatDetailUiState(
                messages = listOf(
                    Message(
                        id = "1",
                        conversationId = "conv1",
                        role = "user",
                        content = "What is 2+2?",
                        createdAt = java.time.Instant.now()
                    )
                ),
                isTypingIndicatorVisible = true,
                isLoading = false
            ),
            onSendMessage = {},
            onRetryStreaming = {},
            onRegenerateMessage = {},
            onDismissError = {},
            onExportConversation = {},
            onNavigateUp = {}
        )
    }
}

@Preview(showBackground = true, name = "ChatDetail â€“ Streaming")
@Composable
private fun ChatDetailStreamingPreview() {
    AppTheme(dynamicColor = false) {
        ChatDetailScreenContent(
            uiState = ChatDetailUiState(
                messages = listOf(
                    Message(
                        id = "1",
                        conversationId = "conv1",
                        role = "user",
                        content = "Tell me a joke",
                        createdAt = java.time.Instant.now()
                    )
                ),
                streamingText = "Why don't scientists trust atoms? Because they make up...",
                isStreaming = true,
                isLoading = false
            ),
            onSendMessage = {},
            onRetryStreaming = {},
            onRegenerateMessage = {},
            onDismissError = {},
            onExportConversation = {},
            onNavigateUp = {}
        )
    }
}

@Preview(showBackground = true, name = "ChatDetail â€“ Retry banner")
@Composable
private fun ChatDetailRetryPreview() {
    AppTheme(dynamicColor = false) {
        ChatDetailScreenContent(
            uiState = ChatDetailUiState(
                error = DomainError.StreamingInterrupted(message = "Connection lost."),
                showRetryOption = true,
                isLoading = false
            ),
            onSendMessage = {},
            onRetryStreaming = {},
            onRegenerateMessage = {},
            onDismissError = {},
            onExportConversation = {},
            onNavigateUp = {}
        )
    }
}

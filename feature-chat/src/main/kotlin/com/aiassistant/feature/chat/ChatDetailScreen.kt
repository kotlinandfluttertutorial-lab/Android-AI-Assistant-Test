/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatDetailScreen.kt
 * Purpose    : Redesigned active chat screen (Task 50.4) with:
 *              - Pill-shaped MessageInputBar, gradient send button,
 *                character counter, accessory row (camera/attach/compare/more)
 *              - core-ui TypingIndicator replacing the inline implementation
 *              - SharedTransitionLayout placeholder for hero transition
 *              - Provider-aware ChatBubble with long-press menu
 *
 * Architecture Layer : Feature (feature-chat) — Compose UI layer.
 *                      State driven by ChatDetailViewModel.
 *
 * Dependencies       : core-ui (ChatBubble, MarkdownText, ErrorBanner,
 *                      TypingIndicator, AppColors, spacing, elevation),
 *                      domain models.
 *
 * Requirements       : 2.1, 2.2, 2.5, 2.6, 2.7, 2.8, 2.10
 * ============================================================
 */
package com.aiassistant.feature.chat

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.components.ChatBubble
import com.aiassistant.core.ui.components.ChatBubbleRole
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.MarkdownText
import com.aiassistant.core.ui.motion.TypingIndicator
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.ExportFormat
import com.aiassistant.domain.model.Message

// Max characters per message (Requirement 2.1)
private const val MAX_MESSAGE_LENGTH = 32_000

// ── Entry point ───────────────────────────────────────────────────────────────

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

// ── Stateless screen ──────────────────────────────────────────────────────────

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

    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        val total = uiState.messages.size +
            (if (uiState.streamingText.isNotEmpty()) 1 else 0) +
            (if (uiState.isTypingIndicatorVisible) 1 else 0)
        if (total > 0) listState.animateScrollToItem(total - 1)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = { ExportMenuButton(onExportConversation) }
            )
        },
        bottomBar = {
            Column {
                if (uiState.continuationSuggestion != null) {
                    ContinuationSuggestionChip(
                        suggestion = uiState.continuationSuggestion,
                        onAccept = onAcceptContinuationSuggestion,
                        onDismiss = onDismissContinuationSuggestion
                    )
                }
                // ── Redesigned pill MessageInputBar ────────────────────────
                PillMessageInputBar(
                    isStreaming = uiState.isStreaming,
                    preFillText = uiState.preFillInputText,
                    onPreFillConsumed = onPreFillConsumed,
                    onSendMessage = onSendMessage,
                    onAttachClick = { /* TODO: launch file picker */ },
                    onCameraClick = { /* TODO: open camera feature */ },
                    onCompareClick = { /* TODO: open comparison mode */ }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // General error
            if (uiState.error != null && !uiState.showRetryOption) {
                ErrorBanner(message = uiState.error.message, modifier = Modifier.fillMaxWidth())
            }
            // Retry banner
            if (uiState.showRetryOption) {
                RetryBanner(
                    errorMessage = uiState.error?.message ?: "Streaming was interrupted.",
                    onRetry = onRetryStreaming,
                    onDismiss = onDismissError
                )
            }
            // On-device persistent indicator
            AnimatedVisibility(visible = uiState.isRunningOnDevice, enter = fadeIn(), exit = fadeOut()) {
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
                items(uiState.messages, key = { it.id }) { message ->
                    MessageItem(message = message, onRegenerate = { onRegenerateMessage(message.id) })
                }
                if (uiState.streamingText.isNotEmpty()) {
                    item(key = "streaming") {
                        StreamingMessageItem(text = uiState.streamingText)
                    }
                }
                // ── core-ui TypingIndicator replaces inline implementation ─
                if (uiState.isTypingIndicatorVisible) {
                    item(key = "typing") {
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
                                modifier = Modifier.semantics {
                                    contentDescription = "Assistant is typing"
                                }
                            ) {
                                TypingIndicator(
                                    modifier = Modifier.padding(
                                        horizontal = MaterialTheme.spacing.md,
                                        vertical = MaterialTheme.spacing.sm
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Message items ─────────────────────────────────────────────────────────────

@Composable
private fun MessageItem(message: Message, onRegenerate: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    if (message.role == "user") {
        ChatBubble(
            text = message.content,
            role = ChatBubbleRole.USER,
            contentDescription = "You: ${message.content}",
            onLongPress = { clipboardManager.setText(AnnotatedString(message.content)) },
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        AssistantMessageItem(message = message, onRegenerate = onRegenerate)
    }
}

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
                Text(
                    text = "Assistant",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                MarkdownText(
                    markdown = message.content,
                    contentDescription = message.content,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.semantics { contentDescription = "Message actions" }
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Copy as plain text") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = {
                        menuExpanded = false
                        clipboardManager.setText(AnnotatedString(message.content))
                    }
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                    onClick = {
                        menuExpanded = false
                        shareText(context, message.content)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Regenerate") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                    onClick = {
                        menuExpanded = false
                        onRegenerate()
                    }
                )
            }
        }
    }
}

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
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                MarkdownText(
                    markdown = text,
                    contentDescription = text,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ── Redesigned pill MessageInputBar ──────────────────────────────────────────

/**
 * Pill-shaped input bar with:
 * - Character counter (shown when nearing the 32,000 char limit)
 * - Gradient send button (disabled when blank or streaming)
 * - Accessory row: camera / attach / compare / more
 */
@Composable
private fun PillMessageInputBar(
    isStreaming: Boolean,
    onSendMessage: (String) -> Unit,
    preFillText: String = "",
    onPreFillConsumed: () -> Unit = {},
    onAttachClick: () -> Unit,
    onCameraClick: () -> Unit,
    onCompareClick: () -> Unit
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    val isDark = isSystemInDarkTheme()
    val gradientStart = if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight
    val gradientEnd = if (isDark) AppColors.gradientEndDark else AppColors.gradientEndLight
    val charCount = inputText.length
    val isOverLimit = charCount > MAX_MESSAGE_LENGTH

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
        Column(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.xs
            )
        ) {
            // ── Accessory row ──────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCameraClick,
                    modifier = Modifier.semantics { contentDescription = "Open camera" }
                ) {
                    Icon(
                        Icons.Filled.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onAttachClick,
                    modifier = Modifier.semantics { contentDescription = "Attach file" }
                ) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onCompareClick,
                    modifier = Modifier.semantics { contentDescription = "Compare models" }
                ) {
                    Icon(
                        Icons.Filled.CompareArrows,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                // Character counter — visible when > 90% of limit
                if (charCount > MAX_MESSAGE_LENGTH * 9 / 10) {
                    Text(
                        text = "$charCount / $MAX_MESSAGE_LENGTH",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOverLimit) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(end = MaterialTheme.spacing.xs)
                    )
                }
            }

            // ── Pill input row ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pill text field
                TextField(
                    value = inputText,
                    onValueChange = { if (it.length <= MAX_MESSAGE_LENGTH) inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(28.dp))
                        .semantics { contentDescription = "Message input" },
                    placeholder = {
                        Text("Type a message…", style = MaterialTheme.typography.bodyMedium)
                    },
                    maxLines = 5,
                    enabled = !isStreaming,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(28.dp)
                )

                Spacer(Modifier.width(MaterialTheme.spacing.xs))

                // ── Gradient send button ──────────────────────────────────
                val canSend = inputText.isNotBlank() && !isStreaming && !isOverLimit
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (canSend) {
                                Brush.linearGradient(listOf(gradientStart, gradientEnd))
                            } else {
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.colorScheme.surfaceVariant
                                    )
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            val trimmed = inputText.trim()
                            if (trimmed.isNotEmpty()) {
                                onSendMessage(trimmed)
                                inputText = ""
                            }
                        },
                        enabled = canSend,
                        modifier = Modifier
                            .matchParentSize()
                            .semantics { contentDescription = "Send message" }
                    ) {
                        if (isStreaming) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = if (canSend) {
                                    Color.White
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Banners and chips (unchanged logic) ──────────────────────────────────────

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
                .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(MaterialTheme.spacing.xs))
            Text(
                "Running on device",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun RetryBanner(errorMessage: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    ErrorBanner(
        message = errorMessage,
        onRetry = onRetry,
        onDismiss = onDismiss,
        contentDescription = "Streaming interrupted: $errorMessage. Tap Retry to reconnect.",
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ContinuationSuggestionChip(
    suggestion: com.aiassistant.domain.model.ContextSuggestion,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "AI suggestion: ${suggestion.displayText}" },
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            AssistChip(
                onClick = onAccept,
                label = {
                    Text(suggestion.displayText, style = MaterialTheme.typography.labelMedium)
                },
                modifier = Modifier.weight(1f)
                    .semantics { contentDescription = "Tap to continue: ${suggestion.displayText}" }
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(MaterialTheme.spacing.lg)
                    .semantics { contentDescription = "Dismiss suggestion" }
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(MaterialTheme.spacing.md)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportMenuButton(onExportConversation: (ExportFormat) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.semantics { contentDescription = "Export conversation" }
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Export as Markdown") },
                onClick = {
                    menuExpanded = false
                    onExportConversation(ExportFormat.MARKDOWN)
                }
            )
            DropdownMenuItem(
                text = { Text("Export as PDF") },
                onClick = {
                    menuExpanded = false
                    onExportConversation(ExportFormat.PDF)
                }
            )
        }
    }
}

// ── Utility ───────────────────────────────────────────────────────────────────

internal fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share message"))
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/ChatBubble.kt
 * Purpose    : Production-quality chat bubble composable (Phase 5 upgrade).
 *
 *              Changes from Phase 5:
 *              - UserMessageBubble: asymmetric radius (tail bottom-end),
 *                text selection via BasicText or SelectionContainer,
 *                long-press triggers action menu callback.
 *              - AssistantMessageBubble: no background for short prose
 *                (≤ PROSE_THRESHOLD chars), subtle tonal surface for longer
 *                content and when code blocks are present.
 *              - AiModeIndicator chip shown above the first assistant
 *                message in a turn (isFirstInTurn = true).
 *              - Expand/collapse for long responses (> COLLAPSE_LINE_LIMIT
 *                lines) with animateContentSize.
 *              - MessageActionRow shown below assistant bubbles.
 *
 * Architecture Layer : Core-UI — shared design system.
 *                      Consumed by feature-chat ChatDetailScreen.
 * Requirements       : 2.5–2.8, 23.1–23.4
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.AppShapes
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing

// ── Constants ─────────────────────────────────────────────────────────────────

private val BUBBLE_RADIUS_LARGE = AppShapes.bubbleTail.let { 18.dp }
private val BUBBLE_RADIUS_TAIL  = AppShapes.bubbleTail
private val AVATAR_SIZE         = 28.dp
private val MAX_BUBBLE_WIDTH    = 320.dp
/** Lines before expand/collapse is offered for assistant messages. */
private const val COLLAPSE_LINE_LIMIT = 20
/** Character threshold below which no surface background is drawn for prose. */
private const val PROSE_THRESHOLD = 300

/** Identifies the sender of a chat message. */
enum class ChatBubbleRole { USER, ASSISTANT }

// ── User message bubble ───────────────────────────────────────────────────────

/**
 * User message bubble — right-aligned, primary color, tail bottom-end.
 *
 * Long-press triggers [onLongPress] for the copy/share menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onLongPress: (() -> Unit)? = null
) {
    val a11yDesc = contentDescription ?: "You: $text"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.spacing.xxl,
                end = MaterialTheme.spacing.xs,
                top = MaterialTheme.spacing.xs,
                bottom = MaterialTheme.spacing.xs
            )
            .semantics { this.contentDescription = a11yDesc },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart    = BUBBLE_RADIUS_LARGE,
                topEnd      = BUBBLE_RADIUS_LARGE,
                bottomStart = BUBBLE_RADIUS_LARGE,
                bottomEnd   = BUBBLE_RADIUS_TAIL
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .widthIn(max = MAX_BUBBLE_WIDTH)
                .clip(
                    RoundedCornerShape(
                        topStart    = BUBBLE_RADIUS_LARGE,
                        topEnd      = BUBBLE_RADIUS_LARGE,
                        bottomStart = BUBBLE_RADIUS_LARGE,
                        bottomEnd   = BUBBLE_RADIUS_TAIL
                    )
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress ?: {}
                )
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.md,
                        vertical = MaterialTheme.spacing.sm
                    )
                )
            }
        }
    }
}

// ── Assistant message bubble ──────────────────────────────────────────────────

/**
 * Assistant message bubble — left-aligned.
 *
 * For short prose responses (< [PROSE_THRESHOLD] chars): no background surface,
 * text renders directly on the screen background for a clean reading experience.
 *
 * For longer responses or when [forceBackground] is `true`: subtle tonal surface.
 *
 * @param text              Message text (may contain Markdown).
 * @param aiMode            The AI mode that produced this message.
 * @param isFirstInTurn     When `true`, shows the [AiModeIndicator] above the bubble.
 * @param isStreaming       When `true`, shows the blinking cursor instead of full text.
 * @param onRegenerate      Regenerate callback for the action row.
 * @param onShareText       Share callback for the action row.
 * @param forceBackground   Force the tonal background regardless of text length.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
    aiMode: AiMode = AiMode.CLOUD,
    isFirstInTurn: Boolean = false,
    isStreaming: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onShareText: ((String) -> Unit)? = null,
    onThumbsUp: (() -> Unit)? = null,
    onThumbsDown: (() -> Unit)? = null,
    forceBackground: Boolean = false
) {
    val isDark = isSystemInDarkTheme()
    val useBackground = forceBackground || text.length > PROSE_THRESHOLD
    val surfaceColor = if (isDark) AppColors.surfaceTonal2Dark else AppColors.surfaceTonal2Light

    var expanded by remember { mutableStateOf(true) }
    val isLong = text.count { it == '\n' } >= COLLAPSE_LINE_LIMIT

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = MaterialTheme.spacing.xs,
                end = MaterialTheme.spacing.xxl,
                top = MaterialTheme.spacing.xs,
                bottom = 0.dp
            )
    ) {
        // AI mode indicator above first assistant message in this turn
        if (isFirstInTurn) {
            AiModeIndicator(
                mode = aiMode,
                modifier = Modifier.padding(
                    start = AVATAR_SIZE + MaterialTheme.spacing.xs,
                    bottom = 2.dp
                )
            )
        }

        Row(
            verticalAlignment = Alignment.Bottom
        ) {
            // Avatar
            AssistantAvatar(aiMode = aiMode)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))

            Column(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                // Bubble surface (only when needed)
                val bubbleModifier = Modifier
                    .widthIn(max = MAX_BUBBLE_WIDTH)
                    .then(
                        if (useBackground) {
                            Modifier
                                .clip(
                                    RoundedCornerShape(
                                        topStart    = BUBBLE_RADIUS_TAIL,
                                        topEnd      = BUBBLE_RADIUS_LARGE,
                                        bottomStart = BUBBLE_RADIUS_LARGE,
                                        bottomEnd   = BUBBLE_RADIUS_LARGE
                                    )
                                )
                        } else Modifier
                    )
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness    = Spring.StiffnessMediumLow
                        )
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress ?: {}
                    )

                val textContent: @Composable () -> Unit = {
                    if (isStreaming) {
                        StreamingMessage(
                            text = text,
                            modifier = Modifier.padding(
                                horizontal = if (useBackground) MaterialTheme.spacing.md else 0.dp,
                                vertical   = if (useBackground) MaterialTheme.spacing.sm else 2.dp
                            )
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = if (isLong && !expanded) COLLAPSE_LINE_LIMIT else Int.MAX_VALUE,
                                overflow = if (isLong && !expanded) TextOverflow.Ellipsis else TextOverflow.Clip,
                                modifier = Modifier.padding(
                                    horizontal = if (useBackground) MaterialTheme.spacing.md else 0.dp,
                                    vertical   = if (useBackground) MaterialTheme.spacing.sm else 2.dp
                                )
                            )
                        }
                    }
                }

                if (useBackground) {
                    Surface(
                        modifier = bubbleModifier,
                        color = surfaceColor,
                        shape = RoundedCornerShape(
                            topStart    = BUBBLE_RADIUS_TAIL,
                            topEnd      = BUBBLE_RADIUS_LARGE,
                            bottomStart = BUBBLE_RADIUS_LARGE,
                            bottomEnd   = BUBBLE_RADIUS_LARGE
                        )
                    ) {
                        textContent()
                    }
                } else {
                    Box(modifier = bubbleModifier) {
                        textContent()
                    }
                }

                // Expand/collapse button for long responses
                if (isLong && !isStreaming) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.padding(top = 0.dp)
                    ) {
                        Text(
                            text = if (expanded) "Show less" else "Show more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Message action row (not shown while streaming)
                if (!isStreaming) {
                    MessageActionRow(
                        messageText = text,
                        onRegenerate = onRegenerate,
                        onShareText = onShareText,
                        onThumbsUp = onThumbsUp,
                        onThumbsDown = onThumbsDown,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

// ── Unified ChatBubble (backward compat) ──────────────────────────────────────

/**
 * Unified chat bubble composable.
 * New code should prefer [UserMessageBubble] and [AssistantMessageBubble] directly.
 */
@Composable
fun ChatBubble(
    text: String,
    role: ChatBubbleRole,
    contentDescription: String? = null,
    providerLabel: String? = null,
    aiMode: AiMode = providerLabel?.let { AiMode.CLOUD } ?: AiMode.CLOUD,
    isFirstInTurn: Boolean = false,
    isStreaming: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    when (role) {
        ChatBubbleRole.USER -> UserMessageBubble(
            text = text,
            contentDescription = contentDescription,
            onLongPress = onLongPress,
            modifier = modifier
        )
        ChatBubbleRole.ASSISTANT -> AssistantMessageBubble(
            text = text,
            aiMode = aiMode,
            isFirstInTurn = isFirstInTurn,
            isStreaming = isStreaming,
            onLongPress = onLongPress,
            onRegenerate = onRegenerate,
            modifier = modifier
        )
    }
}

// ── Assistant avatar ──────────────────────────────────────────────────────────

@Composable
private fun AssistantAvatar(aiMode: AiMode) {
    val isDark = isSystemInDarkTheme()
    val containerColor = when (aiMode) {
        AiMode.GEMMA, AiMode.ON_DEVICE_RAG ->
            if (isDark) AppColors.gemmaContainerDark else AppColors.gemmaContainerLight
        AiMode.CLOUD ->
            MaterialTheme.colorScheme.primaryContainer
        AiMode.RAG ->
            if (isDark) AppColors.ragContainerDark else AppColors.ragContainerLight
    }
    val iconColor = when (aiMode) {
        AiMode.GEMMA, AiMode.ON_DEVICE_RAG ->
            if (isDark) AppColors.gemmaOnContainerDark else AppColors.gemmaOnContainerLight
        AiMode.CLOUD ->
            MaterialTheme.colorScheme.onPrimaryContainer
        AiMode.RAG ->
            if (isDark) AppColors.ragOnContainerDark else AppColors.ragOnContainerLight
    }

    Box(
        modifier = Modifier
            .size(AVATAR_SIZE)
            .clip(CircleShape)
            .then(
                Modifier.semantics { contentDescription = "AI assistant avatar" }
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(AVATAR_SIZE),
            shape = CircleShape,
            color = containerColor
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.Ai.Assistant,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "UserMessageBubble")
@Composable
private fun UserPreview() {
    AppTheme(dynamicColor = false) {
        UserMessageBubble(text = "Hello, can you help me write a Kotlin function?")
    }
}

@Preview(showBackground = true, name = "AssistantBubble — Gemma, first in turn")
@Composable
private fun AssistantGemmaPreview() {
    AppTheme(dynamicColor = false) {
        AssistantMessageBubble(
            text = "Of course! Here is a simple example function.",
            aiMode = AiMode.GEMMA,
            isFirstInTurn = true,
            onRegenerate = {}
        )
    }
}

@Preview(showBackground = true, name = "AssistantBubble — RAG with background")
@Composable
private fun AssistantRagPreview() {
    AppTheme(dynamicColor = false) {
        AssistantMessageBubble(
            text = "Based on your documents, the answer is: " + "a".repeat(350),
            aiMode = AiMode.RAG,
            isFirstInTurn = true,
            onRegenerate = {},
            onShareText = {}
        )
    }
}

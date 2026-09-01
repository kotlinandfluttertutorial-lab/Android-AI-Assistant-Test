/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/ChatBubble.kt
 * Purpose    : Redesigned chat-bubble composable (Task 50.4) with asymmetric
 *              corner radii, optional provider-avatar slot, and a long-press
 *              action callback for copy/share/regenerate menus.
 *
 * Architecture Layer : Core-UI — shared design system.
 *                      Consumed by feature-chat ChatDetailScreen.
 *                      Never imported from domain or data layers.
 *
 * Dependencies       : Compose Material 3, AppColors, MaterialTheme.spacing.
 *
 * Design Decision    : Asymmetric radii (large on three corners, small on the
 *                      "tail" corner) visually communicate the message direction
 *                      without relying on colour alone — satisfying both the
 *                      design spec and accessibility requirements.
 *                      USER: bottom-right corner = 4 dp (the "tail").
 *                      ASSISTANT: bottom-left corner = 4 dp (the "tail").
 *                      Provider avatar is rendered as a small circle using the
 *                      first letter of [providerLabel] so the UI works without
 *                      bundling LLM-provider icon assets.
 *                      Long-press opens the action menu (copy / share / regenerate)
 *                      via the [onLongPress] callback — keeping the action menu
 *                      accessible to users who cannot use the overflow MoreVert button.
 *
 * Requirements       : 2.5, 23.1, 23.2, 23.4
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing

// ── Bubble shape constants ────────────────────────────────────────────────────

private val BUBBLE_RADIUS_LARGE = 18.dp
private val BUBBLE_RADIUS_TAIL = 4.dp
private val AVATAR_SIZE = 28.dp
private val MAX_BUBBLE_WIDTH = 300.dp

/** Identifies the sender of a chat message. */
enum class ChatBubbleRole {
    /** A message sent by the human user. */
    USER,

    /** A message sent by the AI assistant. */
    ASSISTANT
}

/**
 * A single redesigned chat-bubble composable with asymmetric corner radii,
 * optional provider avatar, and long-press action support.
 *
 * @param text                Message text to display.
 * @param role                [ChatBubbleRole.USER] or [ChatBubbleRole.ASSISTANT].
 * @param contentDescription  TalkBack description. Auto-generated when null.
 * @param providerLabel       Short LLM provider label shown in the assistant avatar
 *                            (e.g. "GPT", "Gemini", "Claude"). Null = generic AI icon.
 * @param onLongPress         Called when the user long-presses the bubble.  Callers
 *                            should show a copy/share/regenerate bottom sheet or menu.
 * @param modifier            Applied to the outer [Row] container.
 */
@Composable
fun ChatBubble(
    text: String,
    role: ChatBubbleRole,
    contentDescription: String? = null,
    providerLabel: String? = null,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isUser = role == ChatBubbleRole.USER
    val isDark = isSystemInDarkTheme()

    // ── Colours and shape (extracted helpers to reduce cognitive complexity) ──
    val bubbleColor = chatBubbleColor(isUser, isDark)
    val textColor = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface
    val bubbleShape = chatBubbleShape(isUser)

    val a11yDesc = contentDescription ?: if (isUser) "You: $text" else "Assistant: $text"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (isUser) MaterialTheme.spacing.xxl else MaterialTheme.spacing.xs,
                end = if (isUser) MaterialTheme.spacing.xs else MaterialTheme.spacing.xxl,
                top = MaterialTheme.spacing.xs,
                bottom = MaterialTheme.spacing.xs
            )
            .semantics { this.contentDescription = a11yDesc },
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // ── Assistant avatar (left of bubble) ─────────────────────────────
        if (!isUser) {
            AssistantAvatar(providerLabel = providerLabel)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
        }

        // ── Bubble surface ────────────────────────────────────────────────
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier
                .widthIn(max = MAX_BUBBLE_WIDTH)
                .clip(bubbleShape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress ?: {}
                )
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.sm
                )
            )
        }
    }
}

// ── Provider avatar ───────────────────────────────────────────────────────────

@Composable
private fun AssistantAvatar(providerLabel: String?) {
    Box(
        modifier = Modifier
            .size(AVATAR_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (providerLabel != null) {
            Text(
                text = providerLabel.take(2).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// Helper extraction to reduce complexity in ChatBubble
@Composable
private fun chatBubbleColor(isUser: Boolean, isDark: Boolean) = if (isUser) {
    MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.9f else 1f)
} else {
    if (isDark) AppColors.surfaceTonal2Dark else AppColors.surfaceTonal2Light
}

private fun chatBubbleShape(isUser: Boolean) = if (isUser) {
    RoundedCornerShape(
        topStart = BUBBLE_RADIUS_LARGE,
        topEnd = BUBBLE_RADIUS_LARGE,
        bottomStart = BUBBLE_RADIUS_LARGE,
        bottomEnd = BUBBLE_RADIUS_TAIL // user "tail"
    )
} else {
    RoundedCornerShape(
        topStart = BUBBLE_RADIUS_TAIL, // assistant "tail"
        topEnd = BUBBLE_RADIUS_LARGE,
        bottomStart = BUBBLE_RADIUS_LARGE,
        bottomEnd = BUBBLE_RADIUS_LARGE
    )
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "ChatBubble — User")
@Composable
private fun ChatBubbleUserPreview() {
    AppTheme(dynamicColor = false) {
        ChatBubble(text = "Hello, can you help me write a Kotlin function?", role = ChatBubbleRole.USER)
    }
}

@Preview(showBackground = true, name = "ChatBubble — Assistant (GPT)")
@Composable
private fun ChatBubbleAssistantPreview() {
    AppTheme(dynamicColor = false) {
        ChatBubble(
            text = "Of course! Here's a simple example.",
            role = ChatBubbleRole.ASSISTANT,
            providerLabel = "GPT"
        )
    }
}

@Preview(showBackground = true, name = "ChatBubble — Assistant (Gemini, dark)")
@Composable
private fun ChatBubbleAssistantDarkPreview() {
    AppTheme(dynamicColor = false, themeMode = com.aiassistant.core.ui.ThemeMode.DARK) {
        ChatBubble(
            text = "Dark mode response bubble.",
            role = ChatBubbleRole.ASSISTANT,
            providerLabel = "Ge"
        )
    }
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : ChatBubble.kt
 * Purpose    : ChatBubble — core-ui module component
 *
 * Architecture Layer : Core-UI
 * Pattern Used       : Kotlin Class
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
 * Module     : core-ui
 * File       : ChatBubble.kt
 * Purpose    : ChatBubble — core-ui module component
 *
 * Architecture Layer : Core-UI
 * Pattern Used       : Kotlin Class
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
 * ChatBubble.kt
 *
 * Purpose: A reusable chat-bubble composable for AI and user messages.
 * Architecture: core-ui â€” shared design system; consumed by chat feature module.
 * Dependencies: Compose Material 3, AppTheme tokens.
 *
 * Design decisions:
 * - Two visual roles: [ChatBubbleRole.USER] and [ChatBubbleRole.ASSISTANT]. User bubbles
 *   are right-aligned with primary-container fill; assistant bubbles are left-aligned
 *   with surface-variant fill.
 * - Both roles include an icon + role-label so the sender is never indicated by colour
 *   alone â€” satisfying the "no colour-only status indicator" accessibility requirement.
 * - A [contentDescription] parameter allows callers to provide a meaningful TalkBack
 *   label (e.g. "Assistant: Hello, how can I help?").
 * - All colours are sourced from [MaterialTheme.colorScheme]; no hardcoded values.
 * - Requirements: 2.5, 23.1, 23.2, 23.4
 */

package com.aiassistant.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing

/** Identifies the sender of a chat message. */
enum class ChatBubbleRole {
    /** A message sent by the human user. */
    USER,

    /** A message sent by the AI assistant. */
    ASSISTANT
}

/**
 * A single chat-bubble composable.
 *
 * @param text               The message text to display inside the bubble.
 * @param role               Whether this is a [ChatBubbleRole.USER] or
 *                           [ChatBubbleRole.ASSISTANT] bubble.
 * @param contentDescription Optional TalkBack / accessibility description. When `null`
 *                           the bubble is described as "[role]: [text]" automatically.
 * @param modifier           Optional [Modifier] applied to the outer row container.
 */
@Composable
fun ChatBubble(text: String, role: ChatBubbleRole, contentDescription: String? = null, modifier: Modifier = Modifier) {
    val isUser = role == ChatBubbleRole.USER
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = if (isUser) Icons.Filled.Person else Icons.Filled.SmartToy
    val roleLabel = if (isUser) "You" else "Assistant"
    val a11yDescription = contentDescription ?: "$roleLabel: $text"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (isUser) MaterialTheme.spacing.xxl else MaterialTheme.spacing.sm,
                end = if (isUser) MaterialTheme.spacing.sm else MaterialTheme.spacing.xxl,
                top = MaterialTheme.spacing.xs,
                bottom = MaterialTheme.spacing.xs
            )
            .semantics { this.contentDescription = a11yDescription },
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Icon(
                imageVector = icon,
                contentDescription = null, // described by the row's semantic
                tint = textColor,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
        }

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.spacing.sm)) {
                // Role label â€” icon + text so sender is never conveyed by colour alone
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isUser) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                    }
                    Text(
                        text = roleLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.75f)
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.xs)
                )
            }
        }
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "ChatBubble â€“ User")
@Composable
private fun ChatBubbleUserPreview() {
    AppTheme(dynamicColor = false) {
        ChatBubble(
            text = "Hello, can you help me write a Kotlin function?",
            role = ChatBubbleRole.USER
        )
    }
}

@Preview(showBackground = true, name = "ChatBubble â€“ Assistant")
@Composable
private fun ChatBubbleAssistantPreview() {
    AppTheme(dynamicColor = false) {
        ChatBubble(
            text = "Of course! Here's a simple example of a Kotlin function.",
            role = ChatBubbleRole.ASSISTANT
        )
    }
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/MessageActionRow.kt
 * Purpose    : Production-quality message action row shown below
 *              assistant message bubbles.
 *
 *              Actions: Copy | Share | Regenerate | Thumbs Up | Thumbs Down
 *
 *              Copy action shows an animated checkmark confirmation for
 *              COPY_CONFIRM_DURATION_MS then reverts to the copy icon.
 *
 * Architecture Layer : Core-UI — shared design system.
 * Requirements       : 2.6, 2.7, 23.2, 23.4
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.COPY_CONFIRM_DURATION_MS
import com.aiassistant.core.ui.DURATION_MICRO
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Action row shown below an assistant message bubble.
 *
 * @param messageText       The raw text content of the message (used for copy/share).
 * @param onRegenerate      Called when the user taps Regenerate. Pass `null` to hide
 *                          the button (e.g., for mid-conversation messages).
 * @param onShareText       Called with the message text when the user taps Share.
 * @param onThumbsUp        Called when the user taps the thumbs-up rating.
 * @param onThumbsDown      Called when the user taps the thumbs-down rating.
 * @param thumbsUpActive    Whether the user has already rated this message positively.
 * @param thumbsDownActive  Whether the user has already rated this message negatively.
 * @param modifier          Applied to the root Row.
 */
@Composable
fun MessageActionRow(
    messageText: String,
    onRegenerate: (() -> Unit)? = null,
    onShareText: ((String) -> Unit)? = null,
    onThumbsUp: (() -> Unit)? = null,
    onThumbsDown: (() -> Unit)? = null,
    thumbsUpActive: Boolean = false,
    thumbsDownActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Copy button with animated confirmation
        IconButton(
            onClick = {
                clipboardManager.setText(AnnotatedString(messageText))
                copied = true
                scope.launch {
                    delay(COPY_CONFIRM_DURATION_MS.toLong())
                    copied = false
                }
            },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .semantics {
                    contentDescription = if (copied) "Copied to clipboard" else "Copy message"
                }
        ) {
            AnimatedContent(
                targetState = copied,
                transitionSpec = {
                    fadeIn(tween(DURATION_MICRO)) togetherWith fadeOut(tween(DURATION_MICRO))
                },
                label = "copyIcon"
            ) { isCopied ->
                Icon(
                    imageVector = if (isCopied) AppIcons.Chat.CopyDone else AppIcons.Chat.Copy,
                    contentDescription = null,
                    tint = if (isCopied) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Share button
        if (onShareText != null) {
            IconButton(
                onClick = { onShareText(messageText) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "Share message" }
            ) {
                Icon(
                    imageVector = AppIcons.Chat.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Regenerate button
        if (onRegenerate != null) {
            IconButton(
                onClick = onRegenerate,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "Regenerate response" }
            ) {
                Icon(
                    imageVector = AppIcons.Chat.Regenerate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Thumbs up
        if (onThumbsUp != null) {
            IconButton(
                onClick = onThumbsUp,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "Rate response positively" }
            ) {
                Icon(
                    imageVector = if (thumbsUpActive) AppIcons.Chat.ThumbUp else AppIcons.Chat.ThumbUpOutlined,
                    contentDescription = null,
                    tint = if (thumbsUpActive) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Thumbs down
        if (onThumbsDown != null) {
            IconButton(
                onClick = onThumbsDown,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "Rate response negatively" }
            ) {
                Icon(
                    imageVector = if (thumbsDownActive) AppIcons.Chat.ThumbDown else AppIcons.Chat.ThumbDownOutlined,
                    contentDescription = null,
                    tint = if (thumbsDownActive) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageActionRowPreview() {
    AppTheme(dynamicColor = false) {
        MessageActionRow(
            messageText = "Sample AI response",
            onRegenerate = {},
            onShareText = {},
            onThumbsUp = {},
            onThumbsDown = {}
        )
    }
}

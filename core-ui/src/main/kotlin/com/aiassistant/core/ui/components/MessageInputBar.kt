/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/MessageInputBar.kt
 * Purpose    : Production-quality message input bar (Phases 4.4 & 4.5).
 *
 *              Features:
 *              - Pill-shaped container with surface tonal background
 *              - Multi-line auto-expand up to MAX_INPUT_LINES
 *              - Animated send button (scale spring on enable/disable)
 *              - When AI is generating: shows "Stop generating" state with
 *                animated stop button replacing the send button
 *              - Attachment + voice buttons in accessory slot
 *              - Keyboard-aware IME padding (caller must apply imePadding())
 *              - Accessible: 48dp touch targets, contentDescriptions
 *
 * Architecture Layer : Core-UI — shared design system.
 *                      Consumed by feature-chat ChatDetailScreen.
 *
 * Requirements       : 2.3, 2.4, 23.1, 23.2
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.AppShapes
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.DURATION_QUICK
import com.aiassistant.core.ui.spacing

private const val MAX_INPUT_LINES = 6

/**
 * Production-quality message input bar.
 *
 * @param value             Current input text.
 * @param onValueChange     Called on every text change.
 * @param onSend            Called when the user taps Send (only when not generating).
 * @param onStopGenerating  Called when the user taps Stop (only when generating).
 * @param onAttach          Called when the attach button is tapped. Pass `null` to hide.
 * @param onVoice           Called when the voice button is tapped. Pass `null` to hide.
 * @param isGenerating      `true` while the AI is producing a response. Switches to
 *                          "Stop generating" mode.
 * @param isEnabled         `false` disables the entire input bar (e.g., offline).
 * @param placeholder       Placeholder text shown when the field is empty.
 * @param modifier          Applied to the root Column.
 */
@Composable
fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStopGenerating: () -> Unit = {},
    onAttach: (() -> Unit)? = null,
    onVoice: (() -> Unit)? = null,
    isGenerating: Boolean = false,
    isEnabled: Boolean = true,
    placeholder: String = "Ask anything…",
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val containerBg = if (isDark) AppColors.surfaceTonal2Dark else AppColors.surfaceTonal2Light

    val canSend = value.isNotBlank() && isEnabled && !isGenerating

    // Animated send button scale — bounces in when enabled
    val sendScale by animateFloatAsState(
        targetValue = if (canSend || isGenerating) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "sendButtonScale"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // "Stop generating" hint bar shown above input when generating
        AnimatedVisibility(
            visible = isGenerating,
            enter = fadeIn(tween(DURATION_QUICK)),
            exit = fadeOut(tween(DURATION_QUICK))
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.xs),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = AppShapes.statusBadge
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.md,
                        vertical = MaterialTheme.spacing.xs
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Chat.Stop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                    Text(
                        text = "AI is generating…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs)
                .background(color = containerBg, shape = AppShapes.inputBar),
            verticalAlignment = Alignment.Bottom
        ) {
            // Attach button
            if (onAttach != null) {
                IconButton(
                    onClick = onAttach,
                    enabled = isEnabled && !isGenerating,
                    modifier = Modifier
                        .padding(start = MaterialTheme.spacing.xs)
                        .semantics { contentDescription = "Attach file" }
                ) {
                    Icon(
                        imageVector = AppIcons.Chat.Attach,
                        contentDescription = null,
                        tint = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Text field
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = isEnabled && !isGenerating,
                placeholder = {
                    Text(
                        text = if (isGenerating) "Stop generating to continue typing…" else placeholder,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = (48 * MAX_INPUT_LINES).dp)
                    .semantics { contentDescription = "Message input field" },
                maxLines = MAX_INPUT_LINES,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            // Voice button
            if (onVoice != null && !isGenerating) {
                IconButton(
                    onClick = onVoice,
                    enabled = isEnabled,
                    modifier = Modifier.semantics { contentDescription = "Voice input" }
                ) {
                    Icon(
                        imageVector = AppIcons.Chat.Mic,
                        contentDescription = null,
                        tint = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Send / Stop button — animated transition
            Box(
                modifier = Modifier
                    .padding(end = MaterialTheme.spacing.xs, bottom = MaterialTheme.spacing.xs)
                    .scale(sendScale)
            ) {
                AnimatedContent(
                    targetState = isGenerating,
                    transitionSpec = {
                        fadeIn(tween(DURATION_QUICK)) togetherWith fadeOut(tween(DURATION_QUICK))
                    },
                    label = "sendStopButton"
                ) { generating ->
                    if (generating) {
                        FilledIconButton(
                            onClick = onStopGenerating,
                            modifier = Modifier
                                .size(40.dp)
                                .semantics { contentDescription = "Stop generating" }
                        ) {
                            Icon(
                                imageVector = AppIcons.Chat.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = canSend,
                            modifier = Modifier
                                .size(40.dp)
                                .semantics { contentDescription = "Send message" }
                        ) {
                            Icon(
                                imageVector = AppIcons.Chat.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageInputBarIdlePreview() {
    AppTheme(dynamicColor = false) {
        MessageInputBar(
            value = "",
            onValueChange = {},
            onSend = {},
            modifier = Modifier.padding(8.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MessageInputBarGeneratingPreview() {
    AppTheme(dynamicColor = false) {
        MessageInputBar(
            value = "",
            onValueChange = {},
            onSend = {},
            isGenerating = true,
            modifier = Modifier.padding(8.dp)
        )
    }
}

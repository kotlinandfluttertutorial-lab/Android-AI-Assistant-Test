/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/InlineChatError.kt
 * Purpose    : InlineChatError — inline error message shown inside the chat
 *              message list when a response fails. Includes a Retry button
 *              and maps DomainError codes to user-friendly copy.
 *
 * Architecture Layer : Core-UI — shared design system.
 * Requirements       : 2.8, 28.1–28.4
 * ============================================================
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing

/**
 * Inline error composable shown within the chat message LazyColumn when an
 * AI response generation fails.
 *
 * @param message    User-facing error message (must not contain raw exception text).
 * @param onRetry    Called when the user taps the Retry button. Pass `null` to hide
 *                   the retry button (e.g., for non-retryable errors).
 * @param modifier   Applied to the root Surface.
 */
@Composable
fun InlineChatError(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.xs)
            .semantics { contentDescription = "Error: $message" },
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            Icon(
                imageVector = AppIcons.Status.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (onRetry != null) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.semantics { contentDescription = "Retry" },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(
                        imageVector = AppIcons.Chat.Regenerate,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Retry", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun InlineChatErrorPreview() {
    AppTheme(dynamicColor = false) {
        InlineChatError(
            message = "Couldn't connect to the AI service. Please try again.",
            onRetry = {}
        )
    }
}

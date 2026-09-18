/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/SystemMessage.kt
 * Purpose    : SystemMessage — subtle divider-style label for system events
 *              in the chat list (conversation started, model switched, offline).
 *
 * Architecture Layer : Core-UI — shared design system.
 * Requirements       : 2.9
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing

/**
 * A subtle divider-with-label system message.
 *
 * Example:
 * ```
 * ──────  Conversation started  ──────
 * ──────  Switched to Gemma     ──────
 * ──────  You went offline      ──────
 * ```
 *
 * @param text     The system event description.
 * @param modifier Applied to the root Row.
 */
@Composable
fun SystemMessage(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.md,
                vertical = MaterialTheme.spacing.xs
            )
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SystemMessagePreview() {
    AppTheme(dynamicColor = false) {
        SystemMessage(text = "Conversation started")
    }
}

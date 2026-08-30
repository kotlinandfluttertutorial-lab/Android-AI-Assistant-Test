/**
 * DevOpsChatCard.kt — feature-dashboard module
 *
 * Chat input + response card for the Phase 13 DevOps Assistant.
 * Shows a text field with pre-set quick questions and the AI's
 * grounded answer with citations.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.feature.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aiassistant.feature.dashboard.ChatUiState

private val QUICK_QUESTIONS = listOf(
    "Why did the API fail?",
    "Show open incidents",
    "How do I restart the service?",
    "Summarize today's errors"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevOpsChatCard(
    chatState: ChatUiState,
    onSubmit: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "DevOps Assistant",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Spacer(Modifier.height(12.dp))

            // Quick questions
            if (chatState is ChatUiState.Idle) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    QUICK_QUESTIONS.forEach { q ->
                        AssistChip(
                            onClick = {
                                query = q
                                onSubmit(q)
                            },
                            label = { Text(text = q, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Input field
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Ask a DevOps question" },
                placeholder = { Text("Ask anything about your production system...") },
                trailingIcon = {
                    if (chatState is ChatUiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = {
                                if (query.isNotBlank()) {
                                    onSubmit(query)
                                    query = ""
                                }
                            },
                            enabled = query.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Send,
                                contentDescription = "Submit question"
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (query.isNotBlank()) {
                            onSubmit(query)
                            query = ""
                        }
                    }
                ),
                singleLine = true
            )

            // Answer
            when (chatState) {
                is ChatUiState.Success -> {
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = chatState.result.answer,
                        style = MaterialTheme.typography.bodySmall
                    )

                    // Citations
                    if (chatState.result.citations.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Sources: ${chatState.result.citations.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Tools used
                    if (chatState.result.toolsUsed.isNotEmpty()) {
                        Text(
                            text = "Tools: ${chatState.result.toolsUsed.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is ChatUiState.Error -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = chatState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> { /* Idle / Loading — nothing extra to show */ }
            }
        }
    }
}

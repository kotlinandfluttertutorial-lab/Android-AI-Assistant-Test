/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-meeting
 * File       : MeetingSummaryScreen.kt
 * Purpose    : Compose UI screen for the MeetingSummary feature
 *
 * Architecture Layer : Feature (feature-meeting)
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
 * Module     : feature-meeting
 * File       : MeetingSummaryScreen.kt
 * Purpose    : Compose UI screen for the MeetingSummary feature
 *
 * Architecture Layer : Feature (feature-meeting)
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
 * MeetingSummaryScreen.kt
 *
 * Purpose: Jetpack Compose screen that displays the AI-generated meeting summary,
 *          timestamped transcript, extracted action items, and export controls.
 * Architecture: feature-meeting â€” UI layer; observes MeetingViewModel state (shared
 *               with MeetingRecorderScreen via the nav-graph scoped ViewModel).
 * Dependencies: MeetingViewModel (Hilt), core-ui (MarkdownText, ErrorBanner, spacing),
 *               Compose Material 3
 *
 * Requirements: 19.1
 *
 * Design decisions:
 * - The ViewModel is scoped to the meeting nav-graph so both screens share state
 *   without passing large data objects as nav arguments.
 * - MarkdownText from core-ui renders the AI summary with proper formatting.
 * - Action items are rendered as a LazyColumn of cards for efficient list handling.
 * - Export buttons use Android share sheet (Intent.ACTION_SEND) to avoid
 *   WRITE_EXTERNAL_STORAGE permission requirements.
 * - All interactive elements carry contentDescription for TalkBack (Requirement 23.1).
 */
package com.aiassistant.feature.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.MarkdownText
import com.aiassistant.core.ui.spacing

/**
 * Entry-point composable for the Meeting Summary screen.
 *
 * @param onNavigateBack Called when the user presses the back button.
 * @param viewModel      Hilt-injected [MeetingViewModel] scoped to the meeting nav graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingSummaryScreen(onNavigateBack: () -> Unit = {}, viewModel: MeetingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Meeting Summary") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is MeetingUiState.Complete -> SummaryContent(
                summary = state.summary,
                transcript = state.transcript,
                actionItems = state.actionItems,
                onExportPdf = { viewModel.exportAsPdf(context, state.summary) },
                onExportMarkdown = { viewModel.exportAsMarkdown(context, state.summary) },
                modifier = Modifier.padding(innerPadding)
            )

            is MeetingUiState.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(MaterialTheme.spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                ErrorBanner(
                    message = state.message,
                    onRetry = { viewModel.reset() }
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(MaterialTheme.spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No summary available.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// â”€â”€â”€ Main summary content â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun SummaryContent(
    summary: String,
    transcript: String,
    actionItems: List<ActionItem>,
    onExportPdf: () -> Unit,
    onExportMarkdown: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = MaterialTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        // â”€â”€â”€ Export toolbar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        item {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                OutlinedButton(
                    onClick = onExportPdf,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Export meeting summary as PDF" }
                ) {
                    Text(text = "Export PDF")
                }

                OutlinedButton(
                    onClick = onExportMarkdown,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "Export meeting summary as Markdown"
                        }
                ) {
                    Text(text = "Export Markdown")
                }
            }
        }

        // â”€â”€â”€ Meeting Summary section â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        item {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
            SectionHeader(title = "Meeting Summary")
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
            MarkdownText(
                markdown = summary,
                contentDescription = "Meeting summary: $summary",
                modifier = Modifier.fillMaxWidth()
            )
        }

        // â”€â”€â”€ Action Items section â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (actionItems.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
                SectionHeader(title = "Action Items")
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
            }

            items(
                items = actionItems,
                key = { "${it.assignee}-${it.description}" }
            ) { actionItem ->
                ActionItemCard(actionItem = actionItem)
            }
        }

        // â”€â”€â”€ Transcript section â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (transcript.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
                SectionHeader(title = "Transcript")
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Meeting transcript: $transcript"
                        }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ActionItemCard(actionItem: ActionItem, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Action item for ${actionItem.assignee}: ${actionItem.description}"
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics {}
                )
                Text(
                    text = actionItem.assignee,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = actionItem.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "MeetingSummary â€“ Summary Content")
@Composable
private fun MeetingSummaryContentPreview() {
    AppTheme(dynamicColor = false) {
        SummaryContent(
            summary = """
                ## Overview
                The team discussed the Q3 roadmap and agreed on priorities.
                
                ## Key Decisions
                - Feature X will be prioritised for August release.
                - Alice will lead the API integration work.
                
                ## Action Items
                - [Alice]: Complete the API design document by Friday
                - [Bob]: Schedule a follow-up meeting for next week
            """.trimIndent(),
            transcript = "[10:00] Alice: Good morning everyone.\n[10:01] Bob: Morning!",
            actionItems = listOf(
                ActionItem(
                    assignee = "Alice",
                    description = "Complete the API design document by Friday"
                ),
                ActionItem(
                    assignee = "Bob",
                    description = "Schedule a follow-up meeting for next week"
                )
            ),
            onExportPdf = {},
            onExportMarkdown = {}
        )
    }
}

@Preview(showBackground = true, name = "ActionItemCard")
@Composable
private fun ActionItemCardPreview() {
    AppTheme(dynamicColor = false) {
        ActionItemCard(
            actionItem = ActionItem(
                assignee = "Charlie",
                description = "Review the design document and provide feedback"
            )
        )
    }
}

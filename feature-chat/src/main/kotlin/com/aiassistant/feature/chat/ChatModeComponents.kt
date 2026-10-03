/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatModeComponents.kt
 * Purpose    : Compose components specific to multi-mode chat:
 *                - ExecutionModeSelectorRow  — mode picker chips
 *                - CitationCard              — one RAG source card
 *                - CitationsPanel            — collapsible citation list
 *                - ToolCallProgressRow       — one tool-call status row
 *                - ToolCallsPanel            — collapsible tool-call list
 *                - AgentStepIndicator        — step counter badge
 *
 * Architecture Layer : Feature (feature-chat) — Compose UI
 * Pattern Used       : Stateless composables driven by UiState slices
 *
 * Dependencies       : core-ui tokens, domain agent models
 * ============================================================
 */

package com.aiassistant.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.agent.AgentCitation
import com.aiassistant.domain.agent.AgentToolCall
import com.aiassistant.domain.agent.ChatExecutionMode

// ── Mode selector ─────────────────────────────────────────────────────────────

/**
 * A horizontal row of three [FilterChip]s — one per [ChatExecutionMode].
 *
 * The currently selected chip is shown as selected/filled.
 * Tapping an unselected chip calls [onModeSelected].
 *
 * Accessibility: each chip has a content description that includes the mode
 * display name and a short description so screen readers can distinguish them.
 *
 * @param selectedMode   Currently active mode.
 * @param onModeSelected Callback invoked when the user taps a chip.
 * @param enabled        When false all chips are non-interactive (e.g. while streaming).
 * @param modifier       Applied to the outer [Row].
 */
@Composable
internal fun ExecutionModeSelectorRow(
    selectedMode: ChatExecutionMode,
    onModeSelected: (ChatExecutionMode) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs)
            .semantics { contentDescription = "Execution mode selector" },
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatExecutionMode.all.forEach { mode ->
            val isSelected = mode == selectedMode
            FilterChip(
                selected = isSelected,
                enabled = enabled,
                onClick = { if (!isSelected) onModeSelected(mode) },
                label = {
                    Text(
                        text = ChatExecutionMode.displayName(mode),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = modeIcon(mode),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription =
                        "${ChatExecutionMode.displayName(mode)}: " +
                                "${ChatExecutionMode.description(mode)}" +
                                if (isSelected) " (selected)" else ""
                },
            )
        }
    }
}

private fun modeIcon(mode: ChatExecutionMode) = when (mode) {
    ChatExecutionMode.DIRECT_LLM -> Icons.Filled.QuestionAnswer
    ChatExecutionMode.RAG        -> Icons.Filled.Search
    ChatExecutionMode.AGENT      -> Icons.Filled.SmartToy
}

// ── Citations panel ───────────────────────────────────────────────────────────

/**
 * Collapsible panel that shows RAG source citations after an answer is received.
 *
 * Hidden when [citations] is empty. Starts collapsed; user taps to expand.
 *
 * @param citations List of [AgentCitation] from the last RAG or AGENT response.
 * @param modifier  Applied to the outer [Surface].
 */
@Composable
internal fun CitationsPanel(
    citations: List<AgentCitation>,
    modifier: Modifier = Modifier,
) {
    if (citations.isEmpty()) return

    var expanded by remember(citations) { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Header row ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(
                        horizontal = MaterialTheme.spacing.sm,
                        vertical = MaterialTheme.spacing.xs,
                    )
                    .semantics {
                        contentDescription =
                            if (expanded) "Collapse ${citations.size} source references"
                            else "Expand ${citations.size} source references"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(MaterialTheme.spacing.xs))
                Text(
                    text = "${citations.size} source${if (citations.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            // ── Expandable citation cards ────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = MaterialTheme.spacing.sm,
                            end = MaterialTheme.spacing.sm,
                            bottom = MaterialTheme.spacing.xs,
                        ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    citations.forEachIndexed { index, citation ->
                        CitationCard(citation = citation, index = index + 1)
                    }
                }
            }
        }
    }
}

/**
 * A single RAG source citation card.
 *
 * Displays the document name, page number (if available), similarity score, and
 * a truncated excerpt of the retrieved chunk.
 *
 * @param citation The [AgentCitation] to display.
 * @param index    1-based ordinal shown as a leading badge.
 * @param modifier Applied to the card container.
 */
@Composable
internal fun CitationCard(
    citation: AgentCitation,
    index: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(MaterialTheme.spacing.sm)
            .semantics {
                contentDescription =
                    "Source $index: ${citation.documentName}" +
                            (citation.pageNumber?.let { ", page $it" } ?: "") +
                            ". ${citation.excerpt}"
            },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        // Index badge
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(
                text = "$index",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        // Content
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                Text(
                    text = citation.documentName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                citation.pageNumber?.let { page ->
                    Text(
                        text = "p.$page",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Score pill — only when non-zero
                if (citation.score > 0f) {
                    ScorePill(score = citation.score)
                }
            }
            if (citation.excerpt.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = citation.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ScorePill(score: Float) {
    val pct = (score * 100).toInt()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = "$pct%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

// ── Tool-calls panel ──────────────────────────────────────────────────────────

/**
 * Collapsible panel showing MCP tool invocations made during an AGENT run.
 *
 * Hidden when [toolCalls] is empty. Starts expanded (tool use is notable progress).
 *
 * @param toolCalls   List of [AgentToolCall] records accumulated during the run.
 * @param stepCount   Total reasoning steps — shown alongside tool count.
 * @param modifier    Applied to the outer [Surface].
 */
@Composable
internal fun ToolCallsPanel(
    toolCalls: List<AgentToolCall>,
    stepCount: Int,
    modifier: Modifier = Modifier,
) {
    if (toolCalls.isEmpty() && stepCount == 0) return

    var expanded by remember(toolCalls.size) { mutableStateOf(true) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Header row ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(
                        horizontal = MaterialTheme.spacing.sm,
                        vertical = MaterialTheme.spacing.xs,
                    )
                    .semantics {
                        contentDescription = buildString {
                            if (toolCalls.isNotEmpty()) append("${toolCalls.size} tool call(s). ")
                            if (stepCount > 0) append("$stepCount reasoning step(s). ")
                            append(if (expanded) "Tap to collapse." else "Tap to expand.")
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
                if (toolCalls.isNotEmpty()) {
                    Text(
                        text = "${toolCalls.size} tool${if (toolCalls.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (stepCount > 0) {
                    Text(
                        text = "· $stepCount step${if (stepCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            // ── Expandable tool rows ─────────────────────────────────────
            AnimatedVisibility(
                visible = expanded && toolCalls.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = MaterialTheme.spacing.sm,
                            end = MaterialTheme.spacing.sm,
                            bottom = MaterialTheme.spacing.xs,
                        ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    toolCalls.forEach { call ->
                        ToolCallProgressRow(toolCall = call)
                    }
                }
            }
        }
    }
}

/**
 * A single row showing the status of one MCP tool invocation.
 *
 * States:
 * - Pending   → [Icons.Filled.HourglassEmpty] (output null, not failed)
 * - Succeeded → [Icons.Filled.Check]
 * - Failed    → [Icons.Filled.Error]
 *
 * @param toolCall The [AgentToolCall] record to display.
 * @param modifier Applied to the row container.
 */
@Composable
internal fun ToolCallProgressRow(
    toolCall: AgentToolCall,
    modifier: Modifier = Modifier,
) {
    val (icon, tint, statusLabel) = when {
        toolCall.failed ->
            Triple(Icons.Filled.Error, MaterialTheme.colorScheme.error, "failed")
        toolCall.output != null ->
            Triple(Icons.Filled.Check, MaterialTheme.colorScheme.primary, "completed")
        else ->
            Triple(Icons.Filled.HourglassEmpty, MaterialTheme.colorScheme.onSurfaceVariant, "in progress")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = MaterialTheme.spacing.sm, vertical = 6.dp)
            .semantics {
                contentDescription =
                    "Tool ${toolCall.toolName}: $statusLabel" +
                            if (toolCall.failed && toolCall.errorMessage != null)
                                ". ${toolCall.errorMessage}" else ""
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = toolCall.toolName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (toolCall.durationMs > 0L) {
            Text(
                text = "${toolCall.durationMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Agent step badge ──────────────────────────────────────────────────────────

/**
 * Small badge shown inline in the message list while an AGENT run is in progress.
 *
 * Displays the current reasoning-step count with a spinner-style icon.
 * Hidden when [stepCount] is zero.
 *
 * @param stepCount Current number of reasoning steps.
 * @param modifier  Applied to the outer [Row].
 */
@Composable
internal fun AgentStepIndicator(
    stepCount: Int,
    modifier: Modifier = Modifier,
) {
    if (stepCount <= 0) return

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = MaterialTheme.spacing.sm, vertical = 4.dp)
            .semantics { contentDescription = "Agent reasoning: step $stepCount" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "Step $stepCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

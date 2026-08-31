/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : TicketDetailScreen.kt
 * Purpose    : Redesigned ticket detail screen (Task 50.7) with:
 *              - Inline status selector (segmented-button style)
 *              - FlowRow tag chips
 *              - AI action chips (Summarise / Expand / Add action items)
 *
 * Architecture Layer : Feature (feature-productivity) — Compose UI layer.
 *
 * Dependencies       : core-ui (AppColors, AppType, spacing, elevation),
 *                      domain (TodoItem, Priority).
 *
 * Requirements       : 29.1, 29.2, 24.1
 * ============================================================
 */
package com.aiassistant.feature.productivity

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandCircleDown
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppType
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Priority
import com.aiassistant.domain.model.TodoItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Entry point ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketDetailScreen(
    ticket: TodoItem,
    onNavigateUp: () -> Unit,
    onEditTicket: (TodoItem) -> Unit,
    onStatusChange: (TodoItem, String) -> Unit,
    onAiSummarise: (TodoItem) -> Unit,
    onAiExpand: (TodoItem) -> Unit,
    onAiAddActionItems: (TodoItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ticket Detail", maxLines = 1) },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics { contentDescription = "Navigate back" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onEditTicket(ticket) },
                        modifier = Modifier.semantics { contentDescription = "Edit ticket" },
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        TicketDetailContent(
            ticket = ticket,
            onStatusChange = onStatusChange,
            onAiSummarise = onAiSummarise,
            onAiExpand = onAiExpand,
            onAiAddActionItems = onAiAddActionItems,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

// ── Stateless content ─────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun TicketDetailContent(
    ticket: TodoItem,
    onStatusChange: (TodoItem, String) -> Unit,
    onAiSummarise: (TodoItem) -> Unit,
    onAiExpand: (TodoItem) -> Unit,
    onAiAddActionItems: (TodoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light
    val accentColor = when (ticket.priority) {
        Priority.HIGH   -> if (isDark) AppColors.ticketUrgentDark else AppColors.ticketUrgentLight
        Priority.MEDIUM -> if (isDark) AppColors.ticketInProgressDark else AppColors.ticketInProgressLight
        Priority.LOW    -> if (isDark) AppColors.ticketClosedDark else AppColors.ticketClosedLight
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.spacing.screenEdge),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        Spacer(Modifier.height(MaterialTheme.spacing.xs))

        // ── Title + priority chip ─────────────────────────────────────────
        Text(
            text = ticket.title.ifBlank { "Untitled ticket" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { contentDescription = "Ticket title: ${ticket.title}" },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        ticket.priority.value.replaceFirstChar { it.uppercaseChar() },
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = accentColor.copy(alpha = 0.15f),
                    labelColor = accentColor,
                ),
                border = AssistChipDefaults.assistChipBorder(enabled = false),
                modifier = Modifier.semantics {
                    contentDescription = "${ticket.priority.value} priority"
                },
            )
            ticket.dueDate?.let { dueMs ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CalendarToday, contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = " " + Instant.ofEpochMilli(dueMs)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .format(DateTimeFormatter.ofPattern("MMM d, yyyy")),
                        style = AppType.sectionLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Inline status selector ────────────────────────────────────────
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Status: ${ticket.derivedStatus()}" },
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.low),
            colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MaterialTheme.spacing.md),
            ) {
                Text(
                    text = "STATUS",
                    style = AppType.sectionLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    listOf("open", "in_progress", "closed").forEach { s ->
                        val isSelected = ticket.derivedStatus() == s
                        FilterChip(
                            selected = isSelected,
                            onClick = { onStatusChange(ticket, s) },
                            label = {
                                Text(
                                    text = s.replace("_", " ").replaceFirstChar { it.uppercaseChar() },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Set status to $s" },
                        )
                    }
                }
            }
        }

        // ── Description ───────────────────────────────────────────────────
        if (ticket.description.isNotBlank()) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "Description" },
                elevation = CardDefaults.elevatedCardElevation(0.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
            ) {
                Column(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
                    Text(
                        "DESCRIPTION",
                        style = AppType.sectionLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(MaterialTheme.spacing.xs))
                    Text(
                        text = ticket.description,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // ── FlowRow tag chips ─────────────────────────────────────────────
        if (ticket.tags.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "TAGS",
                    style = AppType.sectionLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    ticket.tags.forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.semantics { contentDescription = "Tag: $tag" },
                        )
                    }
                }
            }
        }

        // ── AI action chips ───────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "AI ACTIONS",
                style = AppType.sectionLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.xs))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                AiActionChip(
                    label = "Summarise",
                    icon = Icons.Filled.AutoAwesome,
                    onClick = { onAiSummarise(ticket) },
                    contentDesc = "AI: summarise this ticket",
                )
                AiActionChip(
                    label = "Expand",
                    icon = Icons.Filled.ExpandCircleDown,
                    onClick = { onAiExpand(ticket) },
                    contentDesc = "AI: expand ticket description",
                )
                AiActionChip(
                    label = "Add action items",
                    icon = Icons.Filled.FormatListBulleted,
                    onClick = { onAiAddActionItems(ticket) },
                    contentDesc = "AI: extract action items",
                )
            }
        }

        Spacer(Modifier.height(MaterialTheme.spacing.xl))
    }
}

@Composable
private fun AiActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    contentDesc: String,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = {
            Icon(icon, contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        border = AssistChipDefaults.assistChipBorder(enabled = false),
        modifier = Modifier.semantics { contentDescription = contentDesc },
    )
}

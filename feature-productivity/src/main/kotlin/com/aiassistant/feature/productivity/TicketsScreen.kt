/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : TicketsScreen.kt
 * Purpose    : Redesigned Tickets screen (Task 50.7) — replaces the "Tasks" tab
 *              with "Tickets" branding.  Shows a filterable, pageable list of
 *              TodoItems styled as Kanban-style ticket cards with:
 *              - FilterChipRow with count badges per status/priority
 *              - TicketCard with priority-colored left-border accent + pressScale
 *              - SwipeRevealLayout delete action
 *              - Quick-move status button on each card
 *
 * Architecture Layer : Feature (feature-productivity) — Compose UI layer.
 *                      State driven by ProductivityViewModel.
 *
 * Dependencies       : core-ui (AppColors, SwipeRevealLayout, spacing, elevation,
 *                      pressScale), domain (TodoItem, Priority, SyncStatus).
 *
 * Requirements       : 29.1, 29.2, 24.1
 * ============================================================
 */
package com.aiassistant.feature.productivity

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppType
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.SwipeRevealLayout
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.motion.pressScale
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Priority
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.model.TodoItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Status helpers — derived from existing TodoItem fields ────────────────────
// TodoItem.isCompleted == true → "closed"
// "in_progress" tag present   → "in_progress"
// otherwise                   → "open"

internal fun TodoItem.derivedStatus(): String = when {
    isCompleted -> "closed"
    tags.contains("in_progress") -> "in_progress"
    else -> "open"
}

private val statusCycle = listOf("open", "in_progress", "closed")

private fun TodoItem.nextStatus(): String {
    val idx = statusCycle.indexOf(derivedStatus())
    return statusCycle[(idx + 1) % statusCycle.size]
}

// ── Entry point ───────────────────────────────────────────────────────────────

/**
 * Redesigned Tickets screen — renders todo items styled as Kanban tickets.
 *
 * @param uiState           State from [ProductivityViewModel].
 * @param onTicketClick     Called when user taps a ticket card body → opens [TicketDetailScreen].
 * @param onNewTicket       Called when user taps FAB → opens new ticket editor.
 * @param onDeleteTicket    Called after swipe-to-delete.
 * @param onMoveTicket      Called when user taps quick-move button → advances status.
 * @param onApplyFilter     Called when user changes filter selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketsScreen(
    uiState: ProductivityUiState,
    onTicketClick: (TodoItem) -> Unit,
    onNewTicket: () -> Unit,
    onDeleteTicket: (String) -> Unit,
    onMoveTicket: (TodoItem, String) -> Unit,
    onApplyFilter: (TodoFilterState) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                    ) {
                        Icon(
                            Icons.Outlined.ConfirmationNumber,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Tickets")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewTicket,
                modifier = Modifier.semantics { contentDescription = "Create new ticket" }
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState is ProductivityUiState.Error) {
                ErrorBanner(
                    message = uiState.message,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.md)
                )
            }

            if (uiState is ProductivityUiState.Loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = "Loading tickets" }
                    )
                }
                return@Column
            }

            if (uiState !is ProductivityUiState.TodoList) return@Column

            // ── FilterChipRow with count badges ───────────────────────────
            TicketFilterChipRow(
                uiState = uiState,
                onApplyFilter = onApplyFilter
            )

            Spacer(Modifier.height(MaterialTheme.spacing.xs))

            // ── Ticket list ───────────────────────────────────────────────
            if (uiState.todos.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(MaterialTheme.spacing.lg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No tickets yet. Tap + to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = "No tickets yet. Tap + to create one."
                        }
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        horizontal = MaterialTheme.spacing.md,
                        vertical = MaterialTheme.spacing.sm
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    items(uiState.todos, key = { it.id }) { ticket ->
                        TicketCard(
                            ticket = ticket,
                            onClick = { onTicketClick(ticket) },
                            onDelete = { onDeleteTicket(ticket.id) },
                            onMove = { onMoveTicket(ticket, ticket.nextStatus()) }
                        )
                    }
                }
            }
        }
    }
}

// ── FilterChipRow with count badges ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TicketFilterChipRow(uiState: ProductivityUiState.TodoList, onApplyFilter: (TodoFilterState) -> Unit) {
    val todos = uiState.todos
    val openCount = todos.count { it.derivedStatus() == "open" }
    val inProgressCount = todos.count { it.derivedStatus() == "in_progress" }
    val closedCount = todos.count { it.derivedStatus() == "closed" }
    val urgentCount = todos.count { it.priority == Priority.HIGH }

    LazyRow(
        contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
    ) {
        // Status filters
        item {
            FilterChipWithBadge(
                label = "Open",
                count = openCount,
                selected = uiState.filterState.priority == null && uiState.filterState.showCompleted,
                onClick = {
                    onApplyFilter(uiState.filterState.copy(showCompleted = true, priority = null))
                },
                badgeColor = if (isSystemInDarkTheme()) AppColors.ticketOpenDark else AppColors.ticketOpenLight
            )
        }
        item {
            FilterChipWithBadge(
                label = "In Progress",
                count = inProgressCount,
                selected = false,
                onClick = {
                    onApplyFilter(uiState.filterState.copy(showCompleted = false))
                },
                badgeColor = if (isSystemInDarkTheme()) {
                    AppColors.ticketInProgressDark
                } else {
                    AppColors.ticketInProgressLight
                }
            )
        }
        item {
            FilterChipWithBadge(
                label = "Closed",
                count = closedCount,
                selected = !uiState.filterState.showCompleted,
                onClick = {
                    onApplyFilter(uiState.filterState.copy(showCompleted = false))
                },
                badgeColor = if (isSystemInDarkTheme()) {
                    AppColors.ticketClosedDark
                } else {
                    AppColors.ticketClosedLight
                }
            )
        }
        item { Spacer(Modifier.width(MaterialTheme.spacing.xs)) }
        // Priority filter
        item {
            FilterChipWithBadge(
                label = "Urgent",
                count = urgentCount,
                selected = uiState.filterState.priority == Priority.HIGH,
                onClick = {
                    val newPriority = if (uiState.filterState.priority == Priority.HIGH) {
                        null
                    } else {
                        Priority.HIGH
                    }
                    onApplyFilter(uiState.filterState.copy(priority = newPriority))
                },
                badgeColor = if (isSystemInDarkTheme()) {
                    AppColors.ticketUrgentDark
                } else {
                    AppColors.ticketUrgentLight
                }
            )
        }
        item {
            FilterChip(
                selected = uiState.filterState.priority == null,
                onClick = { onApplyFilter(uiState.filterState.copy(priority = null)) },
                label = { Text("All", style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.semantics { contentDescription = "Show all priorities" }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipWithBadge(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    badgeColor: Color,
    modifier: Modifier = Modifier
) {
    BadgedBox(
        badge = {
            if (count > 0) {
                Badge(containerColor = badgeColor) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        },
        modifier = modifier
    ) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.semantics { contentDescription = "$label: $count tickets" }
        )
    }
}

// ── TicketCard ────────────────────────────────────────────────────────────────

/**
 * Ticket card with:
 * - Priority-colored 4 dp left-border accent
 * - [SwipeRevealLayout] delete action
 * - [pressScale] modifier
 * - Quick-move status button (→ next status in cycle)
 */
@Composable
fun TicketCard(
    ticket: TodoItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light
    val accentColor = when (ticket.priority) {
        Priority.HIGH -> if (isDark) AppColors.ticketUrgentDark else AppColors.ticketUrgentLight
        Priority.MEDIUM -> if (isDark) AppColors.ticketInProgressDark else AppColors.ticketInProgressLight
        Priority.LOW -> if (isDark) AppColors.ticketClosedDark else AppColors.ticketClosedLight
    }

    SwipeRevealLayout(
        modifier = modifier.fillMaxWidth(),
        revealWidth = 72.dp,
        actions = {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = "Delete ticket: ${ticket.title}" }
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            ElevatedCard(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale()
                    .semantics { contentDescription = "Ticket: ${ticket.title}" },
                elevation = CardDefaults.elevatedCardElevation(
                    defaultElevation = MaterialTheme.elevation.low
                ),
                colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = MaterialTheme.spacing.md + 4.dp, // leave room for accent stripe
                            end = MaterialTheme.spacing.sm,
                            top = MaterialTheme.spacing.sm,
                            bottom = MaterialTheme.spacing.sm
                        ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    // Title row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ticket.title.ifBlank { "Untitled ticket" },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // Sync badge
                        TicketSyncBadge(ticket.syncStatus)
                    }

                    // Description preview
                    if (ticket.description.isNotBlank()) {
                        Text(
                            text = ticket.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Bottom row: priority + due date + quick-move
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Priority label chip
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = ticket.priority.value
                                        .replaceFirstChar { it.uppercaseChar() },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "${ticket.priority.value} priority"
                            }
                        )

                        ticket.dueDate?.let { dueMs ->
                            Spacer(Modifier.width(MaterialTheme.spacing.xs))
                            val formatted = Instant.ofEpochMilli(dueMs)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                                .format(DateTimeFormatter.ofPattern("MMM d"))
                            Text(
                                text = "Due $formatted",
                                style = AppType.sectionLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        // Quick-move button → next status
                        Button(
                            onClick = onMove,
                            contentPadding = PaddingValues(
                                horizontal = MaterialTheme.spacing.sm,
                                vertical = 4.dp
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor.copy(alpha = 0.15f),
                                contentColor = accentColor
                            ),
                            modifier = Modifier.semantics {
                                contentDescription = "Move ticket to ${ticket.nextStatus()}"
                            },
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            Icon(
                                Icons.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = ticket.nextStatus()
                                    .replace("_", " ")
                                    .replaceFirstChar { it.uppercaseChar() },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // 4 dp priority-colored left-border accent stripe
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .matchParentSize()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(accentColor)
            )
        }
    }
}

// ── Sync badge ────────────────────────────────────────────────────────────────

@Composable
private fun TicketSyncBadge(syncStatus: SyncStatus) {
    val (icon, desc, tint) = when (syncStatus) {
        SyncStatus.SYNCED -> Triple(Icons.Filled.CheckCircle, "Synced", MaterialTheme.colorScheme.primary)
        SyncStatus.PENDING -> Triple(Icons.Filled.Schedule, "Sync pending", MaterialTheme.colorScheme.tertiary)
        SyncStatus.FAILED -> Triple(Icons.Filled.ErrorOutline, "Sync failed", MaterialTheme.colorScheme.error)
    }
    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = tint,
        modifier = Modifier.size(16.dp).semantics { contentDescription = desc }
    )
}

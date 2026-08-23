/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : CalendarViewScreen.kt
 * Purpose    : Compose UI screen for the CalendarView feature
 *
 * Architecture Layer : Feature (feature-productivity)
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
 * Module     : feature-productivity
 * File       : CalendarViewScreen.kt
 * Purpose    : Compose UI screen for the CalendarView feature
 *
 * Architecture Layer : Feature (feature-productivity)
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
 * CalendarViewScreen.kt
 *
 * Purpose: Stateless Compose screen for the CalendarView sub-feature, rendering a
 *          monthly/weekly calendar grid, event cards, AI meeting-time suggestions,
 *          and a full-screen event editor.
 * Architecture: feature-productivity â€” Compose UI layer; stateless composable driven
 *               by CalendarUiState from CalendarViewModel.
 * Dependencies: core-ui (ErrorBanner, MaterialTheme.spacing), domain (CalendarEvent,
 *               CalendarEventSource), CalendarUiState, CalendarViewMode.
 *
 * Design decisions:
 * - Monthly grid is a manual Column of Rows for precise cell sizing.
 * - DateTimePicker uses Material3 DatePickerDialog + OutlinedTextFields for time input.
 * - All interactive elements carry contentDescriptions (Requirement 23.1).
 * - No color-only indicators â€” icons or text always accompany color-coded states
 *   (Requirement 23.4).
 * - Uses MaterialTheme.colorScheme tokens, never hardcoded colors (Requirement 24.2).
 *
 * Requirements: 8.2, 13.1, 19.1, 23.1, 23.4
 */
package com.aiassistant.feature.productivity.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.model.CalendarEventSource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// â”€â”€â”€ Root composable â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Stateless calendar screen composable.
 *
 * Routes to [CalendarViewContent], [EventEditorContent], or loading/error overlays
 * based on [uiState].
 *
 * @param uiState                  Current state from [CalendarViewModel].
 * @param onSelectDate             Invoked when the user taps a day cell.
 * @param onSwitchViewMode         Invoked when the user toggles monthly/weekly mode.
 * @param onNewEvent               Invoked when the user taps the FAB; receives default start time.
 * @param onEditEvent              Invoked when the user taps an event card.
 * @param onDeleteEvent            Invoked with event id after user confirms deletion.
 * @param onBack                   Invoked when the user presses the back arrow.
 * @param onRequestAiSuggestions   Invoked when the user requests AI meeting suggestions.
 * @param onAcceptSuggestedTime    Invoked with the selected [SuggestedMeetingTime].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarViewScreen(
    uiState: CalendarUiState,
    onSelectDate: (LocalDate) -> Unit,
    onSwitchViewMode: (CalendarViewMode) -> Unit,
    onNewEvent: (Long) -> Unit,
    onEditEvent: (CalendarEvent) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onBack: () -> Unit,
    onRequestAiSuggestions: () -> Unit,
    onAcceptSuggestedTime: (SuggestedMeetingTime) -> Unit,
    onUpdateDraft: (String, String, Long, Long, String?, Boolean) -> Unit,
    onSaveEvent: () -> Unit,
    onBackToCalendar: () -> Unit,
    onEventViewed: (CalendarEvent) -> Unit = {}
) {
    when (uiState) {
        is CalendarUiState.EventEditor -> {
            EventEditorContent(
                state = uiState,
                onUpdateDraft = onUpdateDraft,
                onSave = onSaveEvent,
                onBack = onBackToCalendar
            )
        }
        else -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Calendar") },
                        navigationIcon = {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.semantics {
                                    contentDescription = "Navigate back"
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null
                                )
                            }
                        },
                        actions = {
                            // Monthly/Weekly toggle
                            if (uiState is CalendarUiState.CalendarView) {
                                IconButton(
                                    onClick = {
                                        val next = if (uiState.viewMode == CalendarViewMode.MONTHLY) {
                                            CalendarViewMode.WEEKLY
                                        } else {
                                            CalendarViewMode.MONTHLY
                                        }
                                        onSwitchViewMode(next)
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription =
                                            if (uiState.viewMode == CalendarViewMode.MONTHLY) {
                                                "Switch to weekly view"
                                            } else {
                                                "Switch to monthly view"
                                            }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (uiState.viewMode == CalendarViewMode.MONTHLY) {
                                            Icons.Filled.ViewWeek
                                        } else {
                                            Icons.Filled.CalendarMonth
                                        },
                                        contentDescription = null
                                    )
                                }
                            }
                        }
                    )
                },
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { onNewEvent(System.currentTimeMillis()) },
                        modifier = Modifier.semantics {
                            contentDescription = "Create new event"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null
                        )
                    }
                }
            ) { innerPadding ->
                when (uiState) {
                    is CalendarUiState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.semantics {
                                    contentDescription = "Loading calendar events"
                                }
                            )
                        }
                    }
                    is CalendarUiState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(MaterialTheme.spacing.md)
                        ) {
                            ErrorBanner(message = uiState.message)
                        }
                    }
                    is CalendarUiState.CalendarView -> {
                        CalendarViewContent(
                            state = uiState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            onSelectDate = onSelectDate,
                            onEditEvent = onEditEvent,
                            onDeleteEvent = onDeleteEvent,
                            onRequestAiSuggestions = onRequestAiSuggestions,
                            onAcceptSuggestedTime = onAcceptSuggestedTime,
                            onEventViewed = onEventViewed
                        )
                    }
                    is CalendarUiState.EventEditor -> { /* handled above */ }
                }
            }
        }
    }
}

// â”€â”€â”€ Calendar view content â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun CalendarViewContent(
    state: CalendarUiState.CalendarView,
    modifier: Modifier = Modifier,
    onSelectDate: (LocalDate) -> Unit,
    onEditEvent: (CalendarEvent) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onRequestAiSuggestions: () -> Unit,
    onAcceptSuggestedTime: (SuggestedMeetingTime) -> Unit,
    onEventViewed: (CalendarEvent) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = MaterialTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

        // Google Calendar sync badge
        if (state.isMergingGoogleCalendar) {
            SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        text = "Synced with Google Calendar",
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = "Events merged from Google Calendar"
                }
            )
        }

        // Calendar grid
        when (state.viewMode) {
            CalendarViewMode.MONTHLY -> {
                MonthlyCalendarGrid(
                    state = state,
                    onSelectDate = onSelectDate
                )
            }
            CalendarViewMode.WEEKLY -> {
                WeeklyCalendarList(
                    state = state,
                    onSelectDate = onSelectDate,
                    onEditEvent = onEditEvent,
                    onDeleteEvent = onDeleteEvent
                )
            }
        }

        // Events for selected date (monthly mode shows event list below grid)
        if (state.viewMode == CalendarViewMode.MONTHLY) {
            val selectedDayEvents = state.events.filter { event ->
                eventFallsOnDate(event, state.selectedDate)
            }
            SelectedDayEventsList(
                date = state.selectedDate,
                events = selectedDayEvents,
                eventContextSuggestions = state.eventContextSuggestions,
                onEditEvent = onEditEvent,
                onDeleteEvent = onDeleteEvent,
                onEventViewed = onEventViewed
            )
        }

        // AI suggestions section
        AiSuggestionsSection(
            isLoading = state.isLoadingAiSuggestions,
            suggestions = state.aiSuggestedTimes,
            onRequest = onRequestAiSuggestions,
            onAccept = onAcceptSuggestedTime
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
    }
}

// â”€â”€â”€ Monthly calendar grid â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun MonthlyCalendarGrid(state: CalendarUiState.CalendarView, onSelectDate: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val firstDayOfMonth = state.selectedDate.withDayOfMonth(1)
    val daysInMonth = state.selectedDate.lengthOfMonth()
    // ISO: Monday=1 â€¦ Sunday=7; offset columns before the 1st
    val firstDayOfWeekOffset = firstDayOfMonth.dayOfWeek.value - 1

    val monthLabel = state.selectedDate.month
        .getDisplayName(TextStyle.FULL, Locale.getDefault()) +
        " ${state.selectedDate.year}"

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Month title
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Day-of-week headers: Mon Tue Wed Thu Fri Sat Sun
        Row(modifier = Modifier.fillMaxWidth()) {
            val dayLabels = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
            dayLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Build week rows
        val totalCells = firstDayOfWeekOffset + daysInMonth
        val totalRows = (totalCells + 6) / 7

        repeat(totalRows) { rowIndex ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { colIndex ->
                    val cellIndex = rowIndex * 7 + colIndex
                    val dayNumber = cellIndex - firstDayOfWeekOffset + 1
                    if (dayNumber in 1..daysInMonth) {
                        val cellDate = firstDayOfMonth.plusDays((dayNumber - 1).toLong())
                        val isSelected = cellDate == state.selectedDate
                        val isToday = cellDate == today
                        val eventsOnDay = state.events.filter { eventFallsOnDate(it, cellDate) }
                        DayCell(
                            day = dayNumber,
                            isSelected = isSelected,
                            isToday = isToday,
                            eventCount = eventsOnDay.size,
                            onClick = { onSelectDate(cellDate) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    isSelected: Boolean,
    isToday: Boolean,
    eventCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(2.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Day $day, $eventCount events" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .then(
                    when {
                        isSelected -> Modifier.background(MaterialTheme.colorScheme.primary)
                        isToday -> Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.primary,
                            CircleShape
                        )
                        else -> Modifier
                    }
                )
        ) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    isToday -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }

        // Event dots (max 3)
        if (eventCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(minOf(eventCount, 3)) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

// â”€â”€â”€ Weekly calendar list â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun WeeklyCalendarList(
    state: CalendarUiState.CalendarView,
    onSelectDate: (LocalDate) -> Unit,
    onEditEvent: (CalendarEvent) -> Unit,
    onDeleteEvent: (String) -> Unit
) {
    val monday = state.selectedDate.with(DayOfWeek.MONDAY)
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        repeat(7) { offset ->
            val day = monday.plusDays(offset.toLong())
            val isSelected = day == state.selectedDate
            val eventsOnDay = state.events.filter { eventFallsOnDate(it, day) }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription =
                            "Week day ${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
                    }
                    .clickable { onSelectDate(day) },
                colors = if (isSelected) {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                } else {
                    CardDefaults.cardColors()
                },
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(MaterialTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    Text(
                        text = "${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${day.dayOfMonth}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (eventsOnDay.isEmpty()) {
                        Text(
                            text = "No events",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        eventsOnDay.forEach { event ->
                            WeekDayEventCard(
                                event = event,
                                onEdit = { onEditEvent(event) },
                                onDelete = { onDeleteEvent(event.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekDayEventCard(event: CalendarEvent, onEdit: () -> Unit, onDelete: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete event?") },
            text = { Text("\"${event.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm delete event ${event.title}"
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    modifier = Modifier.semantics {
                        contentDescription = "Cancel delete event"
                    }
                ) { Text("Cancel") }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MaterialTheme.spacing.xs)
            .semantics { contentDescription = "Event: ${event.title}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatTimeRange(event.startTime, event.endTime, event.isAllDay),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onEdit,
            modifier = Modifier.semantics {
                contentDescription = "Edit event: ${event.title}"
            }
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.semantics {
                contentDescription = "Delete event: ${event.title}"
            }
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// â”€â”€â”€ Selected day events list â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun SelectedDayEventsList(
    date: LocalDate,
    events: List<CalendarEvent>,
    onEditEvent: (CalendarEvent) -> Unit,
    onDeleteEvent: (String) -> Unit,
    eventContextSuggestions: Map<String, List<com.aiassistant.domain.model.ContextSuggestion>> = emptyMap(),
    onEventViewed: (CalendarEvent) -> Unit = {}
) {
    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Text(
            text = date.format(formatter),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (events.isEmpty()) {
            Text(
                text = "No events on this day",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            events.forEach { event ->
                SelectedDayEventCard(
                    event = event,
                    onEdit = { onEditEvent(event) },
                    onDelete = { onDeleteEvent(event.id) },
                    contextSuggestions = eventContextSuggestions[event.id] ?: emptyList(),
                    onEventViewed = onEventViewed
                )
            }
        }
    }
}

@Composable
private fun SelectedDayEventCard(
    event: CalendarEvent,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    contextSuggestions: List<com.aiassistant.domain.model.ContextSuggestion> = emptyList(),
    onEventViewed: (CalendarEvent) -> Unit = {}
) {
    // Notify the caller that this event card has been rendered so that the ViewModel
    // can lazily fetch suggestions for it (Requirement 33.2).
    androidx.compose.runtime.LaunchedEffect(event.id) {
        onEventViewed(event)
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete event?") },
            text = { Text("\"${event.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm delete event ${event.title}"
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    modifier = Modifier.semantics {
                        contentDescription = "Cancel delete event"
                    }
                ) { Text("Cancel") }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Event: ${event.title}" },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatTimeRange(event.startTime, event.endTime, event.isAllDay),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Google Calendar badge (icon + text â€” not color-only)
                if (event.source == CalendarEventSource.GOOGLE_CALENDAR) {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = "Google Calendar event",
                        modifier = Modifier
                            .size(16.dp)
                            .semantics { contentDescription = "From Google Calendar" },
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                }

                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.semantics {
                        contentDescription = "Edit event: ${event.title}"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Delete event: ${event.title}"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            val location = event.location
            if (!location.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Context-aware AI suggestion chips (Requirement 33.2)
            // Shown as a non-blocking card below event details when suggestions are available.
            if (contextSuggestions.isNotEmpty()) {
                EventContextSuggestionsCard(suggestions = contextSuggestions)
            }
        }
    }
}

// â”€â”€â”€ AI suggestions section â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

// ─── Event context suggestions card ──────────────────────────────────────────

/**
 * A non-blocking card displayed below calendar event details showing context-aware
 * AI suggestion chips (Requirement 33.2).
 *
 * Chips are tappable (for future handling) but non-dismissible on the calendar view,
 * matching the spec which reserves dismissal to the notes screen only.
 *
 * @param suggestions Non-empty list of suggestions to display.
 */
@Composable
private fun EventContextSuggestionsCard(suggestions: List<com.aiassistant.domain.model.ContextSuggestion>) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "AI suggestions for this event" },
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.xs
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            Text(
                text = "AI Suggestions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                modifier = Modifier.fillMaxWidth()
            ) {
                suggestions.forEach { suggestion ->
                    AssistChip(
                        onClick = { /* future: open AI action */ },
                        label = {
                            Text(
                                text = suggestion.displayText,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "AI suggestion: ${suggestion.displayText}"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AiSuggestionsSection(
    isLoading: Boolean,
    suggestions: List<SuggestedMeetingTime>,
    onRequest: () -> Unit,
    onAccept: (SuggestedMeetingTime) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
    ) {
        TextButton(
            onClick = { isExpanded = !isExpanded },
            modifier = Modifier.semantics {
                contentDescription = if (isExpanded) "Collapse AI suggestions" else "Expand AI suggestions"
            }
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
            Text("âœ¨ AI Meeting Time Suggestions")
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                Button(
                    onClick = onRequest,
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Suggest optimal meeting times" }
                ) {
                    Text("Suggest Optimal Meeting Times")
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Loading AI suggestions" }
                    )
                }

                suggestions.forEach { suggestion ->
                    AssistChip(
                        onClick = { onAccept(suggestion) },
                        label = {
                            Column {
                                Text(
                                    text = formatTimeRange(
                                        suggestion.startTime,
                                        suggestion.endTime,
                                        false
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = suggestion.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Suggested time: ${suggestion.reason}"
                            }
                    )
                }
            }
        }
    }
}

// â”€â”€â”€ Event editor content â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditorContent(
    state: CalendarUiState.EventEditor,
    onUpdateDraft: (String, String, Long, Long, String?, Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val event = state.event

    // Local UI state for text fields and date/time pickers
    var title by remember(event.id) { mutableStateOf(event.title) }
    var description by remember(event.id) { mutableStateOf(event.description) }
    var location by remember(event.id) { mutableStateOf(event.location ?: "") }
    var isAllDay by remember(event.id) { mutableStateOf(event.isAllDay) }
    var startTime by remember(event.id) { mutableStateOf(event.startTime) }
    var endTime by remember(event.id) { mutableStateOf(event.endTime) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    val startDatePickerState = rememberDatePickerState(initialSelectedDateMillis = startTime)
    val endDatePickerState = rememberDatePickerState(initialSelectedDateMillis = endTime)

    if (showStartDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStartDatePicker = false
                        startDatePickerState.selectedDateMillis?.let { ms ->
                            // Preserve time-of-day offset from original
                            val zone = ZoneId.systemDefault()
                            val originalOffset = startTime % 86_400_000L
                            startTime = ms + originalOffset
                            onUpdateDraft(title, description, startTime, endTime, location.ifBlank { null }, isAllDay)
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Confirm start date" }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showStartDatePicker = false },
                    modifier = Modifier.semantics { contentDescription = "Cancel start date" }
                ) { Text("Cancel") }
            }
        ) { DatePicker(state = startDatePickerState) }
    }

    if (showEndDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEndDatePicker = false
                        endDatePickerState.selectedDateMillis?.let { ms ->
                            val originalOffset = endTime % 86_400_000L
                            endTime = ms + originalOffset
                            onUpdateDraft(title, description, startTime, endTime, location.ifBlank { null }, isAllDay)
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Confirm end date" }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEndDatePicker = false },
                    modifier = Modifier.semantics { contentDescription = "Cancel end date" }
                ) { Text("Cancel") }
            }
        ) { DatePicker(state = endDatePickerState) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.isNew) "New Event" else "Edit Event")
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back to calendar" }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = MaterialTheme.spacing.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

            // Title field
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    onUpdateDraft(it, description, startTime, endTime, location.ifBlank { null }, isAllDay)
                },
                label = { Text("Title *") },
                isError = state.titleError != null,
                supportingText = state.titleError?.let { { Text(it) } },
                singleLine = true,
                enabled = !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Event title" }
            )

            // Description field
            OutlinedTextField(
                value = description,
                onValueChange = {
                    description = it
                    onUpdateDraft(title, it, startTime, endTime, location.ifBlank { null }, isAllDay)
                },
                label = { Text("Description") },
                minLines = 2,
                enabled = !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Event description" }
            )

            // All-day toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("All day", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = isAllDay,
                    onCheckedChange = { checked ->
                        isAllDay = checked
                        onUpdateDraft(title, description, startTime, endTime, location.ifBlank { null }, checked)
                    },
                    modifier = Modifier.semantics { contentDescription = "All day event toggle" }
                )
            }

            // Start time
            OutlinedTextField(
                value = formatDateTime(startTime),
                onValueChange = {},
                label = { Text("Start Time") },
                readOnly = true,
                enabled = !state.isSaving && !isAllDay,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (!isAllDay) showStartDatePicker = true }
                    .semantics { contentDescription = "Start time: ${formatDateTime(startTime)}" }
            )

            // End time
            OutlinedTextField(
                value = formatDateTime(endTime),
                onValueChange = {},
                label = { Text("End Time") },
                readOnly = true,
                isError = state.endTimeError != null,
                supportingText = state.endTimeError?.let { { Text(it) } },
                enabled = !state.isSaving && !isAllDay,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (!isAllDay) showEndDatePicker = true }
                    .semantics { contentDescription = "End time: ${formatDateTime(endTime)}" }
            )

            // Location field
            OutlinedTextField(
                value = location,
                onValueChange = {
                    location = it
                    onUpdateDraft(title, description, startTime, endTime, it.ifBlank { null }, isAllDay)
                },
                label = { Text("Location") },
                singleLine = true,
                enabled = !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Event location" }
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            // Save/Cancel buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                TextButton(
                    onClick = onBack,
                    enabled = !state.isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Cancel event editing" }
                ) { Text("Cancel") }

                Button(
                    onClick = onSave,
                    enabled = !state.isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Save event" }
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Save")
                    }
                }
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
        }
    }
}

// â”€â”€â”€ Utilities â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Returns true if [event] falls on [date] (checks start time's date). */
private fun eventFallsOnDate(event: CalendarEvent, date: LocalDate): Boolean {
    val zone = ZoneId.systemDefault()
    val eventDate = Instant.ofEpochMilli(event.startTime)
        .atZone(zone)
        .toLocalDate()
    return eventDate == date
}

/** Formats [startMs] and [endMs] as a short human-readable time range string. */
private fun formatTimeRange(startMs: Long, endMs: Long, isAllDay: Boolean): String {
    if (isAllDay) return "All day"
    val formatter = DateTimeFormatter.ofPattern("h:mm a")
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(startMs).atZone(zone).format(formatter)
    val end = Instant.ofEpochMilli(endMs).atZone(zone).format(formatter)
    return "$start â€“ $end"
}

/** Formats [epochMs] as a date+time string for display in OutlinedTextField labels. */
private fun formatDateTime(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a")
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : HabitListScreen.kt
 * Purpose    : Compose UI screen for the HabitList feature
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
 * File       : HabitListScreen.kt
 * Purpose    : Compose UI screen for the HabitList feature
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
 * HabitListScreen.kt
 *
 * Purpose: Compose screen displaying tracked habits with current streak, today's
 *          completion status, and actions to create, delete, log completion, and
 *          view AI insights.
 *
 * Architecture: feature-productivity â€” Compose UI layer; stateless composable driven
 *               by HabitUiState from HabitViewModel.
 * Dependencies: core-ui (ErrorBanner, MaterialTheme.spacing),
 *               domain (HabitDefinition, HabitEntry, HabitRecurrence), HabitUiState.
 *
 * Design decisions:
 * - Stateless composable: all state + callbacks passed as parameters.
 * - Every interactive element carries a contentDescription (Requirements 23.1, 23.4).
 * - Status indicators always use icon + text label â€” never color-only.
 * - All spacing via MaterialTheme.spacing tokens; all colors via MaterialTheme.colorScheme.
 *
 * Requirements: 13.1, 19.1
 */
package com.aiassistant.feature.productivity.habit

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.HabitDefinition
import com.aiassistant.domain.model.HabitEntry
import com.aiassistant.domain.model.HabitRecurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Habit list screen composable.
 *
 * Renders the full list of tracked habits with streak indicator, today's completion
 * status, a log-completion button, an insights action, and a FAB for creating habits.
 *
 * @param uiState          Current state from [HabitViewModel].
 * @param onHabitClick     Invoked when the user taps a habit card to edit.
 * @param onNewHabit       Invoked when the user taps the FAB.
 * @param onDeleteHabit    Invoked with the habit id when the user taps delete.
 * @param onLogCompletion  Invoked with the habit id to log a completion entry.
 * @param onViewInsights   Invoked with the [HabitDefinition] to open the insights screen.
 * @param viewModel        Optional [HabitViewModel] for streak/completion calculations;
 *                         defaults to creating a standalone helper instance via a
 *                         companion accessor. Injected as parameter for testability.
 */
@Composable
fun HabitListScreen(
    uiState: HabitUiState,
    onHabitClick: (HabitDefinition) -> Unit,
    onNewHabit: () -> Unit,
    onDeleteHabit: (String) -> Unit,
    onLogCompletion: (String) -> Unit,
    onViewInsights: (HabitDefinition) -> Unit,
    streakCalculator: (List<HabitEntry>, HabitRecurrence) -> Int = { entries, rec ->
        HabitStreakHelper.calculateStreak(entries, rec)
    },
    todayCompletedChecker: (List<HabitEntry>, Int) -> Boolean = { entries, freq ->
        HabitStreakHelper.isTodayCompleted(entries, freq)
    }
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Habits") })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewHabit,
                modifier = Modifier.semantics {
                    contentDescription = "Create new habit"
                }
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // â”€â”€ Error banner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState is HabitUiState.Error) {
                ErrorBanner(
                    message = uiState.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.md)
                )
            }

            // â”€â”€ Loading indicator â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState is HabitUiState.Loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Loading habits"
                        }
                    )
                }
                return@Column
            }

            if (uiState !is HabitUiState.HabitList) return@Column

            // â”€â”€ Empty state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState.habits.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Whatshot,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "No habits yet. Tap + to create one.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                return@Column
            }

            // â”€â”€ Habit list â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            LazyColumn(
                contentPadding = PaddingValues(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.sm
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                items(uiState.habits, key = { it.id }) { habit ->
                    val entries = uiState.habitEntriesMap[habit.id] ?: emptyList()
                    val streak = streakCalculator(entries, habit.recurrence)
                    val todayDone = todayCompletedChecker(entries, habit.targetFrequency)
                    HabitCard(
                        habit = habit,
                        streak = streak,
                        isTodayCompleted = todayDone,
                        onClick = { onHabitClick(habit) },
                        onLogCompletion = { onLogCompletion(habit.id) },
                        onViewInsights = { onViewInsights(habit) },
                        onDelete = { onDeleteHabit(habit.id) }
                    )
                }
            }
        }
    }
}

// â”€â”€â”€ Habit card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Displays a single habit card with name, description, recurrence badge, streak,
 * today's completion status, and action buttons.
 *
 * @param habit            The [HabitDefinition] to render.
 * @param streak           Current consecutive-period streak count.
 * @param isTodayCompleted Whether the habit has been completed enough times today.
 * @param onClick          Invoked when the card is tapped (opens editor).
 * @param onLogCompletion  Invoked to log a completion event.
 * @param onViewInsights   Invoked to open the insights screen.
 * @param onDelete         Invoked to delete this habit.
 */
@Composable
private fun HabitCard(
    habit: HabitDefinition,
    streak: Int,
    isTodayCompleted: Boolean,
    onClick: () -> Unit,
    onLogCompletion: () -> Unit,
    onViewInsights: () -> Unit,
    onDelete: () -> Unit
) {
    val streakLabel = when (habit.recurrence) {
        HabitRecurrence.DAILY -> "$streak-day streak"
        HabitRecurrence.WEEKLY -> "$streak-week streak"
    }
    val completionLabel = if (isTodayCompleted) "done today" else "not done today"
    val cardDescription = "Habit: ${habit.name}, streak: $streakLabel, $completionLabel"

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = cardDescription },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            // â”€â”€ Title row with delete button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = habit.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics {
                        contentDescription = "Delete habit: ${habit.name}"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // â”€â”€ Description preview â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (habit.description.isNotBlank()) {
                Text(
                    text = habit.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(MaterialTheme.spacing.xs))

            // â”€â”€ Recurrence badge â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Filled.Repeat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = when (habit.recurrence) {
                        HabitRecurrence.DAILY -> "Daily"
                        HabitRecurrence.WEEKLY -> "Weekly"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // â”€â”€ Streak indicator â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Filled.Whatshot,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "ðŸ”¥ $streakLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // â”€â”€ Today's completion status â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                if (isTodayCompleted) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Done today",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Not done",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // â”€â”€ Action buttons â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                // Log Completion button â€” only shown when not already done today
                if (!isTodayCompleted) {
                    Button(
                        onClick = onLogCompletion,
                        modifier = Modifier.semantics {
                            contentDescription = "Log completion for habit: ${habit.name}"
                        }
                    ) {
                        Text("Log Completion")
                    }
                }

                Spacer(Modifier.weight(1f))

                // Insights TextButton
                TextButton(
                    onClick = onViewInsights,
                    modifier = Modifier.semantics {
                        contentDescription = "View insights for habit: ${habit.name}"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Insights,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.xs))
                    Text("Insights")
                }
            }
        }
    }
}

// â”€â”€â”€ Streak / completion helper (pure functions, no ViewModel dependency) â”€â”€â”€â”€â”€

/**
 * Stateless helper object that exposes [calculateStreak] and [isTodayCompleted] as
 * pure functions â€” used by the composable default parameter lambdas so the screen
 * stays completely stateless.
 */
internal object HabitStreakHelper {

    /**
     * Calculates the current consecutive-period streak for a habit's entries.
     *
     * @param entries    All completion entries for the habit.
     * @param recurrence How often the habit repeats.
     * @return Number of consecutive completed periods.
     */
    fun calculateStreak(entries: List<HabitEntry>, recurrence: HabitRecurrence): Int {
        if (entries.isEmpty()) return 0

        val zone = ZoneId.systemDefault()
        return when (recurrence) {
            HabitRecurrence.DAILY -> {
                val completedDays = entries
                    .map { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
                    .toSet()
                var streak = 0
                var day = LocalDate.now(zone)
                while (completedDays.contains(day)) {
                    streak++
                    day = day.minusDays(1)
                }
                streak
            }
            HabitRecurrence.WEEKLY -> {
                val weekFields = WeekFields.of(Locale.getDefault())
                val completedWeeks = entries.map { entry ->
                    val date = Instant.ofEpochMilli(entry.completedAt).atZone(zone).toLocalDate()
                    date.get(weekFields.weekBasedYear()) * 100 + date.get(weekFields.weekOfWeekBasedYear())
                }.toSet()
                val today = LocalDate.now(zone)
                var streak = 0
                var week = today
                while (true) {
                    val key = week.get(weekFields.weekBasedYear()) * 100 +
                        week.get(weekFields.weekOfWeekBasedYear())
                    if (!completedWeeks.contains(key)) break
                    streak++
                    week = week.minusWeeks(1)
                }
                streak
            }
        }
    }

    /**
     * Returns `true` if [entries] contains at least [targetFrequency] entries logged today.
     *
     * @param entries         All completion entries for the habit.
     * @param targetFrequency Minimum completions required to consider the habit done today.
     */
    fun isTodayCompleted(entries: List<HabitEntry>, targetFrequency: Int): Boolean {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayCount = entries.count { entry ->
            val date = Instant.ofEpochMilli(entry.completedAt).atZone(zone).toLocalDate()
            date == today
        }
        return todayCount >= targetFrequency
    }
}

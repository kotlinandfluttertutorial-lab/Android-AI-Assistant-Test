/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : HabitViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Habit feature
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : MVVM ViewModel
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
 * File       : HabitViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Habit feature
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : MVVM ViewModel
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
 * HabitViewModel.kt
 *
 * Purpose: ViewModel for the Habit Tracker sub-feature â€” manages HabitDefinition CRUD,
 *          habit completion logging, streak/today-completion calculations, and
 *          AI-generated habit insights via GetHabitInsightsUseCase.
 *
 * Architecture: feature-productivity â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (CreateHabitUseCase, DeleteHabitUseCase, LogHabitEntryUseCase,
 *               GetHabitInsightsUseCase, ProductivityRepository),
 *               core-common (DispatcherProvider, ApiResult).
 *
 * Requirements: 13.1, 19.1
 */
package com.aiassistant.feature.productivity.habit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.HabitDefinition
import com.aiassistant.domain.model.HabitEntry
import com.aiassistant.domain.model.HabitRecurrence
import com.aiassistant.domain.repository.ProductivityRepository
import com.aiassistant.domain.usecase.productivity.CreateHabitUseCase
import com.aiassistant.domain.usecase.productivity.DeleteHabitUseCase
import com.aiassistant.domain.usecase.productivity.GetHabitInsightsUseCase
import com.aiassistant.domain.usecase.productivity.LogHabitEntryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Habit Tracker sub-feature.
 *
 * Exposes a [StateFlow] of [HabitUiState] observed by [HabitListScreen],
 * [HabitEditorScreen], and [HabitInsightsScreen]. All blocking I/O is dispatched
 * on [DispatcherProvider.io].
 */
@HiltViewModel
class HabitViewModel @Inject constructor(
    private val createHabitUseCase: CreateHabitUseCase,
    private val deleteHabitUseCase: DeleteHabitUseCase,
    private val logHabitEntryUseCase: LogHabitEntryUseCase,
    private val getHabitInsightsUseCase: GetHabitInsightsUseCase,
    private val productivityRepository: ProductivityRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<HabitUiState>(HabitUiState.Loading)

    /** Observable habit UI state. */
    val uiState: StateFlow<HabitUiState> = _uiState.asStateFlow()

    // â”€â”€ Init â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    init {
        loadHabits()
    }

    // â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Loads all habits and their completion entries, emitting [HabitUiState.HabitList].
     *
     * For each [ApiResult.Success] emission from the habits Flow, all per-habit entry
     * flows are collected concurrently to build the [habitEntriesMap]. On error emits
     * [HabitUiState.Error]; on [ApiResult.NetworkUnavailable] emits an empty list.
     */
    fun loadHabits() {
        viewModelScope.launch {
            _uiState.value = HabitUiState.Loading
            productivityRepository.getHabits().collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        val habits = result.data
                        // Collect entries for all habits concurrently
                        val entriesMap = withContext(dispatchers.io) {
                            habits.associate { habit ->
                                val deferred = async {
                                    val entriesResult = productivityRepository
                                        .getHabitEntries(habit.id)
                                        .firstOrNull()
                                    when (entriesResult) {
                                        is ApiResult.Success -> entriesResult.data
                                        else -> emptyList()
                                    }
                                }
                                habit.id to deferred.await()
                            }
                        }
                        _uiState.value = HabitUiState.HabitList(
                            habits = habits,
                            habitEntriesMap = entriesMap
                        )
                    }
                    is ApiResult.Error -> {
                        _uiState.value = HabitUiState.Error(result.error.message)
                    }
                    is ApiResult.NetworkUnavailable -> {
                        _uiState.value = HabitUiState.HabitList(
                            habits = emptyList(),
                            habitEntriesMap = emptyMap()
                        )
                    }
                    is ApiResult.Loading -> {
                        _uiState.value = HabitUiState.Loading
                    }
                }
            }
        }
    }

    /**
     * Transitions to [HabitUiState.HabitEditor] with a blank [HabitDefinition] draft.
     */
    fun openNewHabit() {
        val now = Instant.now().toEpochMilli()
        val blank = HabitDefinition(
            id = UUID.randomUUID().toString(),
            userId = "",
            name = "",
            description = "",
            recurrence = HabitRecurrence.DAILY,
            targetFrequency = 1,
            createdAt = now,
            updatedAt = now
        )
        _uiState.value = HabitUiState.HabitEditor(habit = blank, isNew = true)
    }

    /**
     * Transitions to [HabitUiState.HabitEditor] for an existing [habit].
     *
     * @param habit The habit definition to edit.
     */
    fun openEditHabit(habit: HabitDefinition) {
        _uiState.value = HabitUiState.HabitEditor(habit = habit, isNew = false)
    }

    /**
     * Updates the draft in [HabitUiState.HabitEditor] without persisting.
     *
     * @param name            Updated habit name.
     * @param description     Updated habit description.
     * @param recurrence      Updated recurrence setting.
     * @param targetFrequency Updated target completion frequency.
     */
    fun updateDraft(name: String, description: String, recurrence: HabitRecurrence, targetFrequency: Int) {
        val current = _uiState.value as? HabitUiState.HabitEditor ?: return
        _uiState.value = current.copy(
            habit = current.habit.copy(
                name = name,
                description = description,
                recurrence = recurrence,
                targetFrequency = targetFrequency,
                updatedAt = Instant.now().toEpochMilli()
            ),
            nameError = null
        )
    }

    /**
     * Validates and saves the current habit draft.
     *
     * - New habits: delegates to [CreateHabitUseCase].
     * - Existing habits: calls [productivityRepository.createHabit] directly (upsert by id).
     * - Sets [HabitUiState.HabitEditor.isSaving] to `true` while the operation runs.
     * - On success transitions back to the habit list via [loadHabits].
     * - On validation failure sets [HabitUiState.HabitEditor.nameError].
     */
    fun saveHabit() {
        val editorState = _uiState.value as? HabitUiState.HabitEditor ?: return
        val habit = editorState.habit

        // â”€â”€ Client-side validation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (habit.name.isBlank()) {
            _uiState.value = editorState.copy(nameError = "Name is required.")
            return
        }
        if (habit.targetFrequency < 1) {
            _uiState.value = editorState.copy(nameError = "Target frequency must be at least 1.")
            return
        }

        _uiState.value = editorState.copy(isSaving = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                if (editorState.isNew) {
                    createHabitUseCase(habit)
                } else {
                    // No UpdateHabitUseCase â€” repository upserts by id
                    productivityRepository.createHabit(habit)
                }
            }
            when (result) {
                is ApiResult.Success -> loadHabits()
                is ApiResult.Error -> {
                    _uiState.value = HabitUiState.Error(result.error.message)
                }
                is ApiResult.NetworkUnavailable -> {
                    // Optimistically go back to list; sync will happen when connected
                    loadHabits()
                }
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Deletes the habit with [habitId] and refreshes the list.
     *
     * @param habitId Unique identifier of the habit to delete.
     */
    fun deleteHabit(habitId: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                deleteHabitUseCase(habitId)
            }
            loadHabits()
        }
    }

    /**
     * Logs a completion entry for the habit with [habitId].
     *
     * Creates a [HabitEntry] with a new UUID and current timestamp, calls
     * [LogHabitEntryUseCase], and on success optimistically appends the entry to
     * [HabitUiState.HabitList.habitEntriesMap].
     *
     * @param habitId Unique identifier of the habit to mark as completed.
     */
    fun logCompletion(habitId: String) {
        val currentList = _uiState.value as? HabitUiState.HabitList ?: return
        val now = Instant.now().toEpochMilli()
        val entry = HabitEntry(
            id = UUID.randomUUID().toString(),
            habitId = habitId,
            userId = "",
            completedAt = now
        )

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                logHabitEntryUseCase(entry)
            }
            if (result is ApiResult.Success) {
                // Optimistically update the entries map
                val updatedMap = currentList.habitEntriesMap.toMutableMap()
                val existing = updatedMap[habitId] ?: emptyList()
                updatedMap[habitId] = existing + result.data
                val latestList = _uiState.value as? HabitUiState.HabitList ?: return@launch
                _uiState.value = latestList.copy(habitEntriesMap = updatedMap)
            }
        }
    }

    /**
     * Transitions to [HabitUiState.HabitInsights] and fetches AI-generated insights
     * for [habit] via [GetHabitInsightsUseCase].
     *
     * @param habit The habit whose insights should be displayed.
     */
    fun openInsights(habit: HabitDefinition) {
        _uiState.value = HabitUiState.HabitInsights(
            habit = habit,
            insightsText = "",
            isLoading = true
        )

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                getHabitInsightsUseCase(habit.id)
            }
            when (result) {
                is ApiResult.Success -> {
                    _uiState.value = HabitUiState.HabitInsights(
                        habit = habit,
                        insightsText = result.data,
                        isLoading = false
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = HabitUiState.Error(result.error.message)
                }
                is ApiResult.NetworkUnavailable -> {
                    _uiState.value = HabitUiState.Error(
                        "No network connection. AI insights require internet access."
                    )
                }
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Navigates back to the habit list by refreshing from the repository.
     */
    fun backToList() {
        loadHabits()
    }

    // â”€â”€ Streak and completion helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Calculates the current consecutive-period streak for a habit's entries.
     *
     * - [HabitRecurrence.DAILY]: counts consecutive days ending today.
     * - [HabitRecurrence.WEEKLY]: counts consecutive calendar weeks ending this week.
     *
     * @param entries    All completion entries for the habit, sorted by any order.
     * @param recurrence How often the habit should repeat.
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
                var day = java.time.LocalDate.now(zone)
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
                    // Represent each week as "year * 100 + weekOfYear" for easy comparison
                    date.get(weekFields.weekBasedYear()) * 100 + date.get(weekFields.weekOfWeekBasedYear())
                }.toSet()
                val today = java.time.LocalDate.now(zone)
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
     * Returns `true` if the habit has been completed at least [targetFrequency] times today.
     *
     * @param entries         All completion entries for the habit.
     * @param targetFrequency Minimum completions required to consider the habit done today.
     */
    fun isTodayCompleted(entries: List<HabitEntry>, targetFrequency: Int): Boolean {
        val zone = ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val todayCount = entries.count { entry ->
            val date = Instant.ofEpochMilli(entry.completedAt).atZone(zone).toLocalDate()
            date == today
        }
        return todayCount >= targetFrequency
    }
}

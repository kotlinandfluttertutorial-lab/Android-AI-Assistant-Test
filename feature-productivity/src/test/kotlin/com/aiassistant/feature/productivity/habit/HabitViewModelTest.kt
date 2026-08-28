/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : HabitViewModelTest.kt
 * Purpose    : Unit tests for HabitViewModel state logic and use cases
 * Architecture: feature-productivity — MVVM ViewModel Tests
 * Requirements: 13.1, 19.1
 * ============================================================
 */
package com.aiassistant.feature.productivity.habit

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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Unit tests for [HabitViewModel] using MockK and Coroutines test utilities.
 * Follows the pattern established in CalendarViewModelTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HabitViewModelTest {

    // ── Shared test dispatcher ────────────────────────────────────────────────

    private val testDispatcher = UnconfinedTestDispatcher()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    // ── Mocks ─────────────────────────────────────────────────────────────────

    private val createHabitUseCase = mockk<CreateHabitUseCase>()
    private val deleteHabitUseCase = mockk<DeleteHabitUseCase>()
    private val logHabitEntryUseCase = mockk<LogHabitEntryUseCase>()
    private val getHabitInsightsUseCase = mockk<GetHabitInsightsUseCase>()
    private val productivityRepository = mockk<ProductivityRepository>()

    private lateinit var viewModel: HabitViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Default behavior for init block (loadHabits)
        every { productivityRepository.getHabits() } returns flowOf(ApiResult.Success(emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun initViewModel() {
        viewModel = HabitViewModel(
            createHabitUseCase = createHabitUseCase,
            deleteHabitUseCase = deleteHabitUseCase,
            logHabitEntryUseCase = logHabitEntryUseCase,
            getHabitInsightsUseCase = getHabitInsightsUseCase,
            productivityRepository = productivityRepository,
            dispatchers = testDispatchers
        )
    }

    private fun makeHabit(id: String = UUID.randomUUID().toString(), name: String = "Test Habit") = HabitDefinition(
        id = id,
        userId = "user123",
        name = name,
        description = "Description",
        recurrence = HabitRecurrence.DAILY,
        targetFrequency = 1,
        createdAt = Instant.now().toEpochMilli(),
        updatedAt = Instant.now().toEpochMilli()
    )

    private fun makeEntry(habitId: String, timestamp: Long = Instant.now().toEpochMilli()) = HabitEntry(
        id = UUID.randomUUID().toString(),
        habitId = habitId,
        userId = "user123",
        completedAt = timestamp
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `loadHabits emits Loading then HabitList with entries`() = runTest {
        val habit = makeHabit()
        val entry = makeEntry(habit.id)

        every { productivityRepository.getHabits() } returns flowOf(ApiResult.Loading, ApiResult.Success(listOf(habit)))
        every { productivityRepository.getHabitEntries(habit.id) } returns flowOf(ApiResult.Success(listOf(entry)))

        val states = mutableListOf<HabitUiState>()

        // Use StandardTestDispatcher so the init-block coroutine pauses until
        // advanceUntilIdle(). This lets the collector subscribe before any emissions,
        // reliably capturing the Loading → HabitList sequence.
        val standardDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val pausedDispatchers = object : DispatcherProvider {
            override val main: CoroutineDispatcher = standardDispatcher
            override val io: CoroutineDispatcher = standardDispatcher
            override val default: CoroutineDispatcher = standardDispatcher
            override val mainImmediate: CoroutineDispatcher = standardDispatcher
            override val unconfined: CoroutineDispatcher = standardDispatcher
        }

        viewModel = HabitViewModel(
            createHabitUseCase = createHabitUseCase,
            deleteHabitUseCase = deleteHabitUseCase,
            logHabitEntryUseCase = logHabitEntryUseCase,
            getHabitInsightsUseCase = getHabitInsightsUseCase,
            productivityRepository = productivityRepository,
            dispatchers = pausedDispatchers
        )

        // Subscribe BEFORE advancing — init coroutine hasn't run yet.
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { states.add(it) }
        }

        // Drain everything: init → loadHabits → emissions
        advanceUntilIdle()

        assertTrue("Sequence should contain Loading state", states.any { it is HabitUiState.Loading })
        assertTrue("Final state should be HabitList", states.last() is HabitUiState.HabitList)

        val finalState = states.last() as HabitUiState.HabitList
        assertEquals(1, finalState.habits.size)
        assertEquals(entry, finalState.habitEntriesMap[habit.id]?.first())

        job.cancel()
    }

    @Test
    fun `logCompletion updates state optimistically`() = runTest {
        val habit = makeHabit()
        val newEntry = makeEntry(habit.id)

        every { productivityRepository.getHabits() } returns flowOf(ApiResult.Success(listOf(habit)))
        every { productivityRepository.getHabitEntries(habit.id) } returns flowOf(ApiResult.Success(emptyList()))
        coEvery { logHabitEntryUseCase(any()) } returns ApiResult.Success(newEntry)

        initViewModel()

        // Ensure we are in HabitList state before logging
        assertTrue(viewModel.uiState.value is HabitUiState.HabitList)

        viewModel.logCompletion(habit.id)

        val state = viewModel.uiState.value as HabitUiState.HabitList
        assertEquals(1, state.habitEntriesMap[habit.id]?.size)
        assertEquals(newEntry, state.habitEntriesMap[habit.id]?.first())
        coVerify { logHabitEntryUseCase(any()) }
    }

    @Test
    fun `saveHabit validation missing name`() = runTest {
        initViewModel()
        viewModel.openNewHabit()
        viewModel.updateDraft(
            name = "",
            description = "Test",
            recurrence = HabitRecurrence.DAILY,
            targetFrequency = 1
        )

        viewModel.saveHabit()

        val state = viewModel.uiState.value as HabitUiState.HabitEditor
        assertEquals("Name is required.", state.nameError)
        coVerify(exactly = 0) { createHabitUseCase(any()) }
    }

    @Test
    fun `saveHabit success case reloads habits`() = runTest {
        val habit = makeHabit()
        coEvery { createHabitUseCase(any()) } returns ApiResult.Success(habit)
        // Mocking the reload after success
        every { productivityRepository.getHabits() } returns flowOf(ApiResult.Success(listOf(habit)))
        every { productivityRepository.getHabitEntries(any()) } returns flowOf(ApiResult.Success(emptyList()))

        initViewModel()
        viewModel.openNewHabit()
        viewModel.updateDraft(
            name = "New Habit",
            description = "Desc",
            recurrence = HabitRecurrence.DAILY,
            targetFrequency = 1
        )

        viewModel.saveHabit()

        // Should transition back to HabitList
        assertTrue(viewModel.uiState.value is HabitUiState.HabitList)
        coVerify { createHabitUseCase(any()) }
    }

    @Test
    fun `openInsights fetches and displays AI insights`() = runTest {
        val habit = makeHabit()
        val insights = "Keep going! You're doing great."
        coEvery { getHabitInsightsUseCase(habit.id) } returns ApiResult.Success(insights)

        initViewModel()
        viewModel.openInsights(habit)

        val state = viewModel.uiState.value as HabitUiState.HabitInsights
        assertEquals(insights, state.insightsText)
        assertEquals(false, state.isLoading)
        coVerify { getHabitInsightsUseCase(habit.id) }
    }

    @Test
    fun `calculateStreak for daily habits`() {
        initViewModel()
        val habitId = "habit1"
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val twoDaysAgo = today.minusDays(2)

        val entries = listOf(
            makeEntry(habitId, today.atStartOfDay(zone).toInstant().toEpochMilli()),
            makeEntry(habitId, yesterday.atStartOfDay(zone).toInstant().toEpochMilli()),
            makeEntry(habitId, twoDaysAgo.atStartOfDay(zone).toInstant().toEpochMilli())
        )

        val streak = viewModel.calculateStreak(entries, HabitRecurrence.DAILY)
        assertEquals(3, streak)
    }

    @Test
    fun `calculateStreak for weekly habits`() {
        initViewModel()
        val habitId = "habit1"
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val lastWeek = today.minusWeeks(1)

        val entries = listOf(
            makeEntry(habitId, today.atStartOfDay(zone).toInstant().toEpochMilli()),
            makeEntry(habitId, lastWeek.atStartOfDay(zone).toInstant().toEpochMilli())
        )

        val streak = viewModel.calculateStreak(entries, HabitRecurrence.WEEKLY)
        assertEquals(2, streak)
    }
}

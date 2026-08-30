/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : CalendarViewModelTest.kt
 * Purpose    : Unit tests for CalendarViewModel state logic and use cases
 * Architecture: feature-productivity — MVVM ViewModel Tests
 * Requirements: 8.2, 13.1, 19.1
 * ============================================================
 */
package com.aiassistant.feature.productivity.calendar

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.model.CalendarEventSource
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.usecase.productivity.CreateCalendarEventUseCase
import com.aiassistant.domain.usecase.productivity.DeleteCalendarEventUseCase
import com.aiassistant.domain.usecase.productivity.GetCalendarEventsUseCase
import com.aiassistant.domain.usecase.productivity.SuggestMeetingTimesUseCase
import com.aiassistant.domain.usecase.suggestions.GetContextSuggestionsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CalendarViewModel] state logic and use case orchestration.
 * Uses MockK for dependency mocking and UnconfinedTestDispatcher for coroutine control.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

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

    private val getCalendarEventsUseCase = mockk<GetCalendarEventsUseCase>()
    private val createCalendarEventUseCase = mockk<CreateCalendarEventUseCase>()
    private val deleteCalendarEventUseCase = mockk<DeleteCalendarEventUseCase>()
    private val suggestMeetingTimesUseCase = mockk<SuggestMeetingTimesUseCase>()
    private val getContextSuggestionsUseCase = mockk<GetContextSuggestionsUseCase>(relaxed = true)

    private lateinit var viewModel: CalendarViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Default success behavior for init block event loading
        every { getCalendarEventsUseCase(any()) } returns flowOf(ApiResult.Success(emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun initViewModel() {
        viewModel = CalendarViewModel(
            getCalendarEventsUseCase = getCalendarEventsUseCase,
            createCalendarEventUseCase = createCalendarEventUseCase,
            deleteCalendarEventUseCase = deleteCalendarEventUseCase,
            suggestMeetingTimesUseCase = suggestMeetingTimesUseCase,
            dispatchers = testDispatchers,
            getContextSuggestionsUseCase = getContextSuggestionsUseCase
        )
    }

    private fun makeCalendarEvent(id: String = UUID.randomUUID().toString(), title: String = "Test Event") =
        CalendarEvent(
            id = id,
            userId = "user123",
            title = title,
            description = "Description",
            startTime = System.currentTimeMillis() + 3600000,
            endTime = System.currentTimeMillis() + 7200000,
            location = "Office",
            isAllDay = false,
            source = CalendarEventSource.LOCAL,
            syncStatus = SyncStatus.SYNCED,
            createdAt = Instant.now().toEpochMilli(),
            updatedAt = Instant.now().toEpochMilli()
        )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `loadEventsForCurrentMonth emits Loading then CalendarView`() = runTest {
        val events = listOf(makeCalendarEvent())
        // Emitting Loading then Success to verify the transition sequence
        every { getCalendarEventsUseCase(any()) } returns flowOf(ApiResult.Loading, ApiResult.Success(events))

        val states = mutableListOf<CalendarUiState>()

        // Switch Dispatchers.Main to StandardTestDispatcher so that viewModelScope.launch
        // inside loadEvents() is paused until advanceUntilIdle(). This lets the collector
        // subscribe before any state transitions have occurred, reliably capturing the
        // Loading → CalendarView sequence.
        // Without this, the class-level UnconfinedTestDispatcher would drain the entire
        // coroutine during CalendarViewModel construction, overwriting Loading before
        // the collector is ever registered.
        val standardDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standardDispatcher)

        val pausedDispatchers = object : DispatcherProvider {
            override val main: CoroutineDispatcher = standardDispatcher
            override val io: CoroutineDispatcher = standardDispatcher
            override val default: CoroutineDispatcher = standardDispatcher
            override val mainImmediate: CoroutineDispatcher = standardDispatcher
            override val unconfined: CoroutineDispatcher = standardDispatcher
        }

        viewModel = CalendarViewModel(
            getCalendarEventsUseCase,
            createCalendarEventUseCase,
            deleteCalendarEventUseCase,
            suggestMeetingTimesUseCase,
            pausedDispatchers,
            getContextSuggestionsUseCase
        )

        // Subscribe BEFORE advancing — ViewModel is constructed but init coroutine hasn't
        // run yet because StandardTestDispatcher pauses until advanceUntilIdle().
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { states.add(it) }
        }

        // Now drain everything: init → loadEventsForCurrentMonth → flow emissions
        advanceUntilIdle()

        // Restore the class-level dispatcher for subsequent tests in this class
        Dispatchers.setMain(testDispatcher)

        // Verify sequence: Loading state is present followed by the populated CalendarView state
        assertTrue("Sequence should contain Loading state", states.any { it is CalendarUiState.Loading })
        assertTrue("Final state should be CalendarView", states.last() is CalendarUiState.CalendarView)
        assertEquals(events, (states.last() as CalendarUiState.CalendarView).events)

        job.cancel()
    }

    @Test
    fun `selectDate updates the state correctly`() = runTest {
        initViewModel()
        val date = LocalDate.of(2026, 12, 25)

        viewModel.selectDate(date)

        val state = viewModel.uiState.value as CalendarUiState.CalendarView
        assertEquals(date, state.selectedDate)
    }

    @Test
    fun `switchViewMode updates the state and reloads events for the new range`() = runTest {
        initViewModel()
        val events = listOf(makeCalendarEvent())
        every { getCalendarEventsUseCase(any()) } returns flowOf(ApiResult.Success(events))

        viewModel.switchViewMode(CalendarViewMode.WEEKLY)

        val state = viewModel.uiState.value as CalendarUiState.CalendarView
        assertEquals(CalendarViewMode.WEEKLY, state.viewMode)
        // Verify reloading: called once in init, and once in switchViewMode
        verify(exactly = 2) { getCalendarEventsUseCase(any()) }
    }

    @Test
    fun `saveEvent validation missing title`() = runTest {
        initViewModel()
        viewModel.openNewEvent()
        viewModel.updateDraft(
            title = "", // Empty title
            description = "Draft",
            startTime = 1000L,
            endTime = 2000L,
            location = null,
            isAllDay = false
        )

        viewModel.saveEvent()

        val state = viewModel.uiState.value as CalendarUiState.EventEditor
        assertEquals("Title is required.", state.titleError)
        coVerify(exactly = 0) { createCalendarEventUseCase(any()) }
    }

    @Test
    fun `saveEvent validation end time before start time`() = runTest {
        initViewModel()
        viewModel.openNewEvent()
        viewModel.updateDraft(
            title = "Valid Title",
            description = "Draft",
            startTime = 2000L,
            endTime = 1000L, // End time before start time
            location = null,
            isAllDay = false
        )

        viewModel.saveEvent()

        val state = viewModel.uiState.value as CalendarUiState.EventEditor
        assertEquals("End time must be after start time.", state.endTimeError)
        coVerify(exactly = 0) { createCalendarEventUseCase(any()) }
    }

    @Test
    fun `saveEvent success case reloads calendar`() = runTest {
        initViewModel()
        val event = makeCalendarEvent(title = "Meeting")
        coEvery { createCalendarEventUseCase(any()) } returns ApiResult.Success(event)

        viewModel.openNewEvent()
        viewModel.updateDraft(
            title = "Meeting",
            description = "Description",
            startTime = 1000L,
            endTime = 2000L,
            location = null,
            isAllDay = false
        )

        viewModel.saveEvent()

        // Success reloads events, transitioning back to CalendarView
        assertTrue(viewModel.uiState.value is CalendarUiState.CalendarView)
        coVerify { createCalendarEventUseCase(any()) }
    }

    @Test
    fun `requestAiMeetingTimeSuggestions sets loading flag and populates suggestions on success`() = runTest {
        initViewModel()
        val isoTimes = listOf("2026-08-30T09:00:00Z", "2026-08-30T14:00:00Z")
        coEvery { suggestMeetingTimesUseCase(any(), any()) } returns ApiResult.Success(isoTimes)

        viewModel.requestAiMeetingTimeSuggestions()

        val state = viewModel.uiState.value as CalendarUiState.CalendarView
        assertFalse("Loading flag should be cleared", state.isLoadingAiSuggestions)
        assertEquals(2, state.aiSuggestedTimes.size)
        assertEquals("AI suggested time slot", state.aiSuggestedTimes[0].reason)
    }
}

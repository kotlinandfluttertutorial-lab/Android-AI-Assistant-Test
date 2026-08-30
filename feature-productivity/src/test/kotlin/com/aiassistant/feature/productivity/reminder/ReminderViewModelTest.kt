/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ReminderViewModelTest.kt
 * Purpose    : Unit tests for ReminderViewModel state logic and use cases.
 * Architecture: feature-productivity — MVVM ViewModel Tests
 * Requirements: 16.3, 16.4, 19.1
 * ============================================================
 */
package com.aiassistant.feature.productivity.reminder

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.usecase.productivity.CreateReminderUseCase
import com.aiassistant.domain.usecase.productivity.DeleteReminderUseCase
import com.aiassistant.domain.usecase.productivity.GetRemindersUseCase
import com.aiassistant.domain.usecase.productivity.GetTodosUseCase
import com.aiassistant.domain.usecase.productivity.SuggestReminderUseCase
import com.aiassistant.domain.usecase.productivity.UpdateReminderUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.Instant
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ReminderViewModel] state logic and use case orchestration.
 * Uses MockK for dependency mocking and UnconfinedTestDispatcher for coroutine control.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderViewModelTest {

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

    private val getRemindersUseCase = mockk<GetRemindersUseCase>()
    private val createReminderUseCase = mockk<CreateReminderUseCase>()
    private val updateReminderUseCase = mockk<UpdateReminderUseCase>()
    private val deleteReminderUseCase = mockk<DeleteReminderUseCase>()
    private val suggestReminderUseCase = mockk<SuggestReminderUseCase>()
    private val getTodosUseCase = mockk<GetTodosUseCase>()
    private val notificationManager = mockk<ReminderNotificationManager>(relaxed = true)

    private lateinit var viewModel: ReminderViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Default behavior for init block
        every { getRemindersUseCase() } returns flowOf(ApiResult.Success(emptyList()))
        every { notificationManager.ensureNotificationChannel() } returns Unit
        every { notificationManager.canScheduleExactAlarms() } returns true
        // openEditor() launches getTodosUseCase() inside viewModelScope — must be stubbed
        // so tests that call openNewReminder() / openEditReminder() don't throw MockKException
        every { getTodosUseCase() } returns flowOf(ApiResult.Success(emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun initViewModel() {
        viewModel = ReminderViewModel(
            getRemindersUseCase = getRemindersUseCase,
            createReminderUseCase = createReminderUseCase,
            updateReminderUseCase = updateReminderUseCase,
            deleteReminderUseCase = deleteReminderUseCase,
            suggestReminderUseCase = suggestReminderUseCase,
            getTodosUseCase = getTodosUseCase,
            notificationManager = notificationManager,
            dispatchers = testDispatchers
        )
    }

    private fun makeReminder(
        id: String = UUID.randomUUID().toString(),
        title: String = "Test Reminder",
        triggerTime: Long = System.currentTimeMillis() + 3600000
    ) = Reminder(
        id = id,
        userId = "user123",
        title = title,
        triggerTime = triggerTime,
        recurrenceRule = null,
        linkedTodoId = null,
        isCompleted = false,
        syncStatus = SyncStatus.SYNCED,
        createdAt = Instant.now().toEpochMilli(),
        updatedAt = Instant.now().toEpochMilli()
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `loadReminders emits Loading then ReminderList`() = runTest {
        val reminders = listOf(makeReminder())
        every { getRemindersUseCase() } returns flowOf(ApiResult.Loading, ApiResult.Success(reminders))

        val states = mutableListOf<ReminderUiState>()

        // Switch Dispatchers.Main to StandardTestDispatcher so that viewModelScope.launch
        // inside loadReminders() is paused until advanceUntilIdle(). This lets the collector
        // subscribe before any coroutine has run, reliably capturing the full Loading →
        // ReminderList sequence. Without this, UnconfinedTestDispatcher would drain the
        // entire coroutine eagerly during ReminderViewModel construction, and the Loading
        // state would already be overwritten before collection starts.
        val standardDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standardDispatcher)

        val pausedDispatchers = object : DispatcherProvider {
            override val main: CoroutineDispatcher = standardDispatcher
            override val io: CoroutineDispatcher = standardDispatcher
            override val default: CoroutineDispatcher = standardDispatcher
            override val mainImmediate: CoroutineDispatcher = standardDispatcher
            override val unconfined: CoroutineDispatcher = standardDispatcher
        }

        // Instantiate to trigger init block call
        viewModel = ReminderViewModel(
            getRemindersUseCase,
            createReminderUseCase,
            updateReminderUseCase,
            deleteReminderUseCase,
            suggestReminderUseCase,
            getTodosUseCase,
            notificationManager,
            pausedDispatchers
        )

        // Subscribe BEFORE advancing — init coroutine is paused on StandardTestDispatcher
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { states.add(it) }
        }

        // Drain everything: init → loadReminders → flow emissions
        advanceUntilIdle()

        // Restore the class-level dispatcher for subsequent tests
        Dispatchers.setMain(testDispatcher)

        assertTrue("Sequence should contain Loading state", states.any { it is ReminderUiState.Loading })
        assertTrue("Final state should be ReminderList", states.last() is ReminderUiState.ReminderList)
        assertEquals(reminders, (states.last() as ReminderUiState.ReminderList).reminders)

        job.cancel()
    }

    @Test
    fun `saveReminder validation missing title`() = runTest {
        initViewModel()
        viewModel.openNewReminder()
        viewModel.updateDraft(
            title = "", // Empty title
            triggerTime = System.currentTimeMillis() + 3600000,
            recurrenceRule = null,
            linkedTodoId = null
        )

        viewModel.saveReminder()

        val state = viewModel.uiState.value as ReminderUiState.ReminderEditor
        assertEquals("Title is required.", state.titleError)
        coVerify(exactly = 0) { createReminderUseCase(any()) }
    }

    @Test
    fun `saveReminder validation past trigger time`() = runTest {
        initViewModel()
        viewModel.openNewReminder()
        viewModel.updateDraft(
            title = "Valid Title",
            triggerTime = System.currentTimeMillis() - 1000, // Past time
            recurrenceRule = null,
            linkedTodoId = null
        )

        viewModel.saveReminder()

        val state = viewModel.uiState.value as ReminderUiState.ReminderEditor
        assertEquals("Trigger time must be in the future.", state.triggerTimeError)
        coVerify(exactly = 0) { createReminderUseCase(any()) }
    }

    @Test
    fun `saveReminder success creates reminder and schedules alarm`() = runTest {
        initViewModel()
        val reminder = makeReminder(title = "New Task")
        coEvery { createReminderUseCase(any()) } returns ApiResult.Success(reminder)
        // getRemindersUseCase returns a Flow (not a suspend function) — use every, not coEvery
        every { getRemindersUseCase() } returns flowOf(ApiResult.Success(listOf(reminder)))

        viewModel.openNewReminder()
        viewModel.updateDraft(
            title = "New Task",
            triggerTime = System.currentTimeMillis() + 3600000,
            recurrenceRule = null,
            linkedTodoId = null
        )

        viewModel.saveReminder()
        advanceUntilIdle()

        // Transitions back to list on success
        assertTrue(viewModel.uiState.value is ReminderUiState.ReminderList)
        coVerify { createReminderUseCase(any()) }
        verify { notificationManager.scheduleAlarm(any()) }
    }

    @Test
    fun `deleteReminder calls cancelAlarm and delete use case`() = runTest {
        val reminder = makeReminder(id = "rem123", title = "To Delete")
        every { getRemindersUseCase() } returns flowOf(ApiResult.Success(listOf(reminder)))
        initViewModel()
        advanceUntilIdle()

        coEvery { deleteReminderUseCase(any()) } returns ApiResult.Success(Unit)

        viewModel.deleteReminder("rem123")
        advanceUntilIdle()

        verify { notificationManager.cancelAlarm("rem123", "To Delete") }
        coVerify { deleteReminderUseCase("rem123") }

        val state = viewModel.uiState.value as ReminderUiState.ReminderList
        assertTrue(state.reminders.isEmpty())
        assertEquals(reminder, state.deletedReminder)
    }

    @Test
    fun `undoDelete restores the reminder`() = runTest {
        val reminder = makeReminder(id = "rem123", title = "Undo Me")
        every { getRemindersUseCase() } returns flowOf(ApiResult.Success(listOf(reminder)))
        initViewModel()
        advanceUntilIdle()

        // 1. Delete
        coEvery { deleteReminderUseCase(any()) } returns ApiResult.Success(Unit)
        viewModel.deleteReminder("rem123")
        advanceUntilIdle()

        // 2. Undo
        coEvery { createReminderUseCase(any()) } returns ApiResult.Success(reminder)
        // Mock getReminders to return the restored reminder on reload
        every { getRemindersUseCase() } returns flowOf(ApiResult.Success(listOf(reminder)))

        viewModel.undoDelete()
        advanceUntilIdle()

        coVerify { createReminderUseCase(reminder) }
        verify { notificationManager.scheduleAlarm(reminder) }

        val state = viewModel.uiState.value as ReminderUiState.ReminderList
        assertEquals(1, state.reminders.size)
    }

    @Test
    fun `suggestReminder transitions through AiSuggesting then opens editor`() = runTest {
        initViewModel()
        val suggestion = makeReminder(title = "AI Suggestion")
        coEvery { suggestReminderUseCase(any()) } returns ApiResult.Success(suggestion)
        every { getTodosUseCase() } returns flowOf(ApiResult.Success(emptyList()))

        val states = mutableListOf<ReminderUiState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { states.add(it) }
        }

        viewModel.suggestReminder("Remind me to call Mom")
        advanceUntilIdle()

        assertTrue("Should contain AiSuggesting state", states.any { it is ReminderUiState.AiSuggesting })
        val finalState = states.last() as ReminderUiState.ReminderEditor
        assertEquals("AI Suggestion", finalState.reminder.title)
        assertTrue(finalState.isNew)

        job.cancel()
    }
}

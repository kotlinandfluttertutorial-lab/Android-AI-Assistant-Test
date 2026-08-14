/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ProductivityViewModelTest.kt
 * Purpose    : Unit tests for ProductivityViewModel, ReminderViewModel (reminder.ProductivityViewModel),
 *              CalendarViewModel, and HabitViewModel state logic
 * Architecture: feature-productivity — MVVM ViewModel Tests
 * Requirements: 21.1
 * ============================================================
 */
package com.aiassistant.feature.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.model.HabitDefinition
import com.aiassistant.domain.model.HabitEntry
import com.aiassistant.domain.model.HabitRecurrence
import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.model.TodoItem
import com.aiassistant.domain.repository.ProductivityRepository
import com.aiassistant.domain.usecase.productivity.CreateCalendarEventUseCase
import com.aiassistant.domain.usecase.productivity.CreateHabitUseCase
import com.aiassistant.domain.usecase.productivity.CreateReminderUseCase
import com.aiassistant.domain.usecase.productivity.CreateTodoUseCase
import com.aiassistant.domain.usecase.productivity.DeleteCalendarEventUseCase
import com.aiassistant.domain.usecase.productivity.DeleteHabitUseCase
import com.aiassistant.domain.usecase.productivity.DeleteReminderUseCase
import com.aiassistant.domain.usecase.productivity.DeleteTodoUseCase
import com.aiassistant.domain.usecase.productivity.GenerateTodosFromPromptUseCase
import com.aiassistant.domain.usecase.productivity.GetCalendarEventsUseCase
import com.aiassistant.domain.usecase.productivity.GetHabitInsightsUseCase
import com.aiassistant.domain.usecase.productivity.GetRemindersUseCase
import com.aiassistant.domain.usecase.productivity.GetTodosUseCase
import com.aiassistant.domain.usecase.productivity.LogHabitEntryUseCase
import com.aiassistant.domain.usecase.productivity.SuggestReminderUseCase
import com.aiassistant.domain.usecase.productivity.UpdateReminderUseCase
import com.aiassistant.domain.usecase.productivity.UpdateTodoUseCase
import com.aiassistant.feature.productivity.calendar.CalendarUiState
import com.aiassistant.feature.productivity.calendar.CalendarViewMode
import com.aiassistant.feature.productivity.calendar.CalendarViewModel
import com.aiassistant.feature.productivity.habit.HabitUiState
import com.aiassistant.feature.productivity.habit.HabitViewModel
import com.aiassistant.feature.productivity.reminder.ProductivityViewModel as ReminderViewModel
import com.aiassistant.feature.productivity.reminder.ReminderNotificationManager
import com.aiassistant.feature.productivity.reminder.ReminderUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProductivityViewModelTest {

    // ── Shared test dispatcher ────────────────────────────────────────────────

    private val testDispatcher = UnconfinedTestDispatcher()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Test data helpers ─────────────────────────────────────────────────────

    private fun makeTodo(id: String = "t1", title: String = "Test Todo") = TodoItem(
        id = id,
        userId = "u1",
        title = title,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    private fun makeReminder(id: String = "r1", title: String = "Test Reminder") = Reminder(
        id = id,
        userId = "u1",
        title = title,
        triggerTime = System.currentTimeMillis() + 3_600_000L,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    private fun makeCalendarEvent(id: String = "e1", title: String = "Test Event") = CalendarEvent(
        id = id,
        userId = "u1",
        title = title,
        startTime = System.currentTimeMillis() + 3_600_000L,
        endTime = System.currentTimeMillis() + 7_200_000L,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    private fun makeHabit(id: String = "h1", name: String = "Exercise") = HabitDefinition(
        id = id,
        userId = "u1",
        name = name,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    private fun makeHabitEntry(id: String = "he1", habitId: String = "h1") = HabitEntry(
        id = id,
        habitId = habitId,
        userId = "u1",
        completedAt = System.currentTimeMillis()
    )

    private fun networkError() = ApiResult.Error(DomainError.NetworkError("network failure"))

    // ─────────────────────────────────────────────────────────────────────────
    // 1. ProductivityViewModel (Todo CRUD)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildTodoViewModel(
        getTodosResult: ApiResult<List<TodoItem>> = ApiResult.Success(emptyList()),
        createResult: ApiResult<TodoItem> = ApiResult.Success(makeTodo()),
        updateResult: ApiResult<TodoItem> = ApiResult.Success(makeTodo()),
        deleteResult: ApiResult<Unit> = ApiResult.Success(Unit),
        generateResult: ApiResult<List<TodoItem>> = ApiResult.Success(emptyList())
    ): ProductivityViewModel {
        val getTodos = mockk<GetTodosUseCase>()
        every { getTodos(any()) } returns flowOf(getTodosResult)

        val createTodo = mockk<CreateTodoUseCase>()
        coEvery { createTodo(any()) } returns createResult

        val updateTodo = mockk<UpdateTodoUseCase>()
        coEvery { updateTodo(any()) } returns updateResult

        val deleteTodo = mockk<DeleteTodoUseCase>()
        coEvery { deleteTodo(any()) } returns deleteResult

        val generateTodos = mockk<GenerateTodosFromPromptUseCase>()
        coEvery { generateTodos(any()) } returns generateResult

        return ProductivityViewModel(
            getTodosUseCase = getTodos,
            createTodoUseCase = createTodo,
            updateTodoUseCase = updateTodo,
            deleteTodoUseCase = deleteTodo,
            generateTodosFromPromptUseCase = generateTodos,
            dispatchers = testDispatchers
        )
    }

    @Test
    fun `loadTodos emits TodoList on success`() = runTest {
        val todos = listOf(makeTodo())
        val vm = buildTodoViewModel(getTodosResult = ApiResult.Success(todos))
        val state = vm.uiState.value
        assertTrue("Expected TodoList but got $state", state is ProductivityUiState.TodoList)
        assertEquals(todos, (state as ProductivityUiState.TodoList).todos)
    }

    @Test
    fun `loadTodos emits Error when use case returns Error`() = runTest {
        val vm = buildTodoViewModel(getTodosResult = networkError())
        val state = vm.uiState.value
        assertTrue("Expected Error but got $state", state is ProductivityUiState.Error)
    }

    @Test
    fun `loadTodos emits TodoList with empty list on NetworkUnavailable`() = runTest {
        val vm = buildTodoViewModel(getTodosResult = ApiResult.NetworkUnavailable)
        val state = vm.uiState.value
        assertTrue("Expected TodoList but got $state", state is ProductivityUiState.TodoList)
        assertTrue((state as ProductivityUiState.TodoList).todos.isEmpty())
    }

    @Test
    fun `openNewTodo transitions to TodoEditor with isNew=true`() = runTest {
        val vm = buildTodoViewModel()
        vm.openNewTodo()
        val state = vm.uiState.value
        assertTrue("Expected TodoEditor but got $state", state is ProductivityUiState.TodoEditor)
        assertTrue((state as ProductivityUiState.TodoEditor).isNew)
    }

    @Test
    fun `openTodo transitions to TodoEditor with isNew=false`() = runTest {
        val todo = makeTodo()
        val vm = buildTodoViewModel()
        vm.openTodo(todo)
        val state = vm.uiState.value
        assertTrue("Expected TodoEditor but got $state", state is ProductivityUiState.TodoEditor)
        val editor = state as ProductivityUiState.TodoEditor
        assertEquals(false, editor.isNew)
        assertEquals(todo, editor.todo)
    }

    @Test
    fun `saveTodo with isNew=true calls createTodoUseCase`() = runTest {
        val todo = makeTodo()
        val createTodo = mockk<CreateTodoUseCase>()
        coEvery { createTodo(any()) } returns ApiResult.Success(todo)

        val getTodos = mockk<GetTodosUseCase>()
        every { getTodos(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val vm = ProductivityViewModel(
            getTodosUseCase = getTodos,
            createTodoUseCase = createTodo,
            updateTodoUseCase = mockk<UpdateTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(todo)
            },
            deleteTodoUseCase = mockk<DeleteTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(Unit)
            },
            generateTodosFromPromptUseCase = mockk<GenerateTodosFromPromptUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(emptyList())
            },
            dispatchers = testDispatchers
        )

        vm.openNewTodo()
        val editor = vm.uiState.value as ProductivityUiState.TodoEditor
        vm.saveTodo(editor.todo)

        coVerify { createTodo(any()) }
        assertTrue(
            "Expected TodoList after save but got ${vm.uiState.value}",
            vm.uiState.value is ProductivityUiState.TodoList
        )
    }

    @Test
    fun `saveTodo with isNew=false calls updateTodoUseCase`() = runTest {
        val todo = makeTodo()
        val updateTodo = mockk<UpdateTodoUseCase>()
        coEvery { updateTodo(any()) } returns ApiResult.Success(todo)

        val getTodos = mockk<GetTodosUseCase>()
        every { getTodos(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val vm = ProductivityViewModel(
            getTodosUseCase = getTodos,
            createTodoUseCase = mockk<CreateTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(todo)
            },
            updateTodoUseCase = updateTodo,
            deleteTodoUseCase = mockk<DeleteTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(Unit)
            },
            generateTodosFromPromptUseCase = mockk<GenerateTodosFromPromptUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(emptyList())
            },
            dispatchers = testDispatchers
        )

        vm.openTodo(todo)
        vm.saveTodo(todo)

        coVerify { updateTodo(any()) }
        assertTrue(vm.uiState.value is ProductivityUiState.TodoList)
    }

    @Test
    fun `saveTodo on Error emits Error state`() = runTest {
        val todo = makeTodo()
        val createTodo = mockk<CreateTodoUseCase>()
        coEvery { createTodo(any()) } returns networkError()

        val getTodos = mockk<GetTodosUseCase>()
        every { getTodos(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val vm = ProductivityViewModel(
            getTodosUseCase = getTodos,
            createTodoUseCase = createTodo,
            updateTodoUseCase = mockk<UpdateTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(todo)
            },
            deleteTodoUseCase = mockk<DeleteTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(Unit)
            },
            generateTodosFromPromptUseCase = mockk<GenerateTodosFromPromptUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(emptyList())
            },
            dispatchers = testDispatchers
        )

        vm.openNewTodo()
        val editor = vm.uiState.value as ProductivityUiState.TodoEditor
        vm.saveTodo(editor.todo)

        assertTrue("Expected Error but got ${vm.uiState.value}", vm.uiState.value is ProductivityUiState.Error)
    }

    @Test
    fun `deleteTodo calls deleteTodoUseCase and reloads list`() = runTest {
        val todo = makeTodo()
        val deleteTodo = mockk<DeleteTodoUseCase>()
        coEvery { deleteTodo(any()) } returns ApiResult.Success(Unit)

        val getTodos = mockk<GetTodosUseCase>()
        every { getTodos(any()) } returns flowOf(ApiResult.Success(listOf(todo)))

        val vm = ProductivityViewModel(
            getTodosUseCase = getTodos,
            createTodoUseCase = mockk<CreateTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(todo)
            },
            updateTodoUseCase = mockk<UpdateTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(todo)
            },
            deleteTodoUseCase = deleteTodo,
            generateTodosFromPromptUseCase = mockk<GenerateTodosFromPromptUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(emptyList())
            },
            dispatchers = testDispatchers
        )

        vm.deleteTodo(todo.id)

        coVerify { deleteTodo(todo.id) }
        assertTrue(vm.uiState.value is ProductivityUiState.TodoList)
    }

    @Test
    fun `generateTodosFromPrompt sets isGeneratingAi then aiSuggestedTodos on success`() = runTest {
        val suggestions = listOf(makeTodo("s1", "AI Todo"))
        val getTodos = mockk<GetTodosUseCase>()
        every { getTodos(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val generateTodos = mockk<GenerateTodosFromPromptUseCase>()
        // First call during init returns empty, second returns suggestions
        coEvery { generateTodos(any()) } returns ApiResult.Success(suggestions)

        val vm = ProductivityViewModel(
            getTodosUseCase = getTodos,
            createTodoUseCase = mockk<CreateTodoUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeTodo())
            },
            updateTodoUseCase = mockk<UpdateTodoUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeTodo())
            },
            deleteTodoUseCase = mockk<DeleteTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(Unit)
            },
            generateTodosFromPromptUseCase = generateTodos,
            dispatchers = testDispatchers
        )

        vm.generateTodosFromPrompt("plan my week")

        val state = vm.uiState.value
        assertTrue("Expected TodoList but got $state", state is ProductivityUiState.TodoList)
        val list = state as ProductivityUiState.TodoList
        assertEquals(suggestions, list.aiSuggestedTodos)
        assertEquals(false, list.isGeneratingAi)
    }

    @Test
    fun `generateTodosFromPrompt emits Error on failure`() = runTest {
        val getTodos = mockk<GetTodosUseCase>()
        every { getTodos(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val generateTodos = mockk<GenerateTodosFromPromptUseCase>()
        coEvery { generateTodos(any()) } returns networkError()

        val vm = ProductivityViewModel(
            getTodosUseCase = getTodos,
            createTodoUseCase = mockk<CreateTodoUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeTodo())
            },
            updateTodoUseCase = mockk<UpdateTodoUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeTodo())
            },
            deleteTodoUseCase = mockk<DeleteTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(Unit)
            },
            generateTodosFromPromptUseCase = generateTodos,
            dispatchers = testDispatchers
        )

        vm.generateTodosFromPrompt("plan my week")

        assertTrue("Expected Error but got ${vm.uiState.value}", vm.uiState.value is ProductivityUiState.Error)
    }

    @Test
    fun `acceptSuggestedTodo removes todo from suggestions and creates it`() = runTest {
        val suggestedTodo = makeTodo("s1", "Suggested")
        val getTodos = mockk<GetTodosUseCase>()
        every { getTodos(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val createTodo = mockk<CreateTodoUseCase>()
        coEvery { createTodo(any()) } returns ApiResult.Success(suggestedTodo)

        val vm = ProductivityViewModel(
            getTodosUseCase = getTodos,
            createTodoUseCase = createTodo,
            updateTodoUseCase = mockk<UpdateTodoUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeTodo())
            },
            deleteTodoUseCase = mockk<DeleteTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(Unit)
            },
            generateTodosFromPromptUseCase = mockk<GenerateTodosFromPromptUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(listOf(suggestedTodo))
            },
            dispatchers = testDispatchers
        )

        vm.generateTodosFromPrompt("prompt")
        val beforeAccept = vm.uiState.value as ProductivityUiState.TodoList
        assertTrue(beforeAccept.aiSuggestedTodos.any { it.id == suggestedTodo.id })

        vm.acceptSuggestedTodo(suggestedTodo)

        coVerify { createTodo(suggestedTodo) }
        val after = vm.uiState.value as ProductivityUiState.TodoList
        assertTrue(after.aiSuggestedTodos.none { it.id == suggestedTodo.id })
    }

    @Test
    fun `dismissSuggestedTodos clears aiSuggestedTodos`() = runTest {
        val getTodos = mockk<GetTodosUseCase>()
        every { getTodos(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val vm = ProductivityViewModel(
            getTodosUseCase = getTodos,
            createTodoUseCase = mockk<CreateTodoUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeTodo())
            },
            updateTodoUseCase = mockk<UpdateTodoUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeTodo())
            },
            deleteTodoUseCase = mockk<DeleteTodoUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(Unit)
            },
            generateTodosFromPromptUseCase = mockk<GenerateTodosFromPromptUseCase>().also {
                coEvery { it(any()) } returns ApiResult.Success(listOf(makeTodo("s1")))
            },
            dispatchers = testDispatchers
        )

        vm.generateTodosFromPrompt("prompt")
        vm.dismissSuggestedTodos()

        val state = vm.uiState.value as ProductivityUiState.TodoList
        assertTrue("Expected empty suggestions", state.aiSuggestedTodos.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. ReminderViewModel (reminder.ProductivityViewModel)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildReminderViewModel(
        getRemindersResult: ApiResult<List<Reminder>> = ApiResult.Success(emptyList()),
        createResult: ApiResult<Reminder> = ApiResult.Success(makeReminder()),
        updateResult: ApiResult<Reminder> = ApiResult.Success(makeReminder()),
        deleteResult: ApiResult<Unit> = ApiResult.Success(Unit),
        suggestResult: ApiResult<Reminder> = ApiResult.Success(makeReminder()),
        canScheduleExact: Boolean = true
    ): ReminderViewModel {
        val getReminders = mockk<GetRemindersUseCase>()
        every { getReminders() } returns flowOf(getRemindersResult)

        val createReminder = mockk<CreateReminderUseCase>()
        coEvery { createReminder(any()) } returns createResult

        val updateReminder = mockk<UpdateReminderUseCase>()
        coEvery { updateReminder(any()) } returns updateResult

        val deleteReminder = mockk<DeleteReminderUseCase>()
        coEvery { deleteReminder(any()) } returns deleteResult

        val suggestReminder = mockk<SuggestReminderUseCase>()
        coEvery { suggestReminder(any()) } returns suggestResult

        val getTodos = mockk<GetTodosUseCase>()
        every { getTodos(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val notificationManager = mockk<ReminderNotificationManager>(relaxed = true)
        every { notificationManager.canScheduleExactAlarms() } returns canScheduleExact

        return ReminderViewModel(
            getRemindersUseCase = getReminders,
            createReminderUseCase = createReminder,
            updateReminderUseCase = updateReminder,
            deleteReminderUseCase = deleteReminder,
            suggestReminderUseCase = suggestReminder,
            getTodosUseCase = getTodos,
            notificationManager = notificationManager,
            dispatchers = testDispatchers
        )
    }

    @Test
    fun `loadReminders emits ReminderList on success`() = runTest {
        val reminders = listOf(makeReminder())
        val vm = buildReminderViewModel(getRemindersResult = ApiResult.Success(reminders))
        val state = vm.uiState.value
        assertTrue("Expected ReminderList but got $state", state is ReminderUiState.ReminderList)
        assertEquals(reminders, (state as ReminderUiState.ReminderList).reminders)
    }

    @Test
    fun `openNewReminder transitions to ReminderEditor with isNew=true and checks canScheduleExactAlarms`() = runTest {
        val notificationManager = mockk<ReminderNotificationManager>(relaxed = true)
        every { notificationManager.canScheduleExactAlarms() } returns true

        val getReminders = mockk<GetRemindersUseCase>()
        every { getReminders() } returns flowOf(ApiResult.Success(emptyList()))

        val vm = ReminderViewModel(
            getRemindersUseCase = getReminders,
            createReminderUseCase = mockk<CreateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeReminder())
            },
            updateReminderUseCase = mockk<UpdateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeReminder())
            },
            deleteReminderUseCase = mockk<DeleteReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            suggestReminderUseCase = mockk<SuggestReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeReminder())
            },
            getTodosUseCase = mockk<GetTodosUseCase>().also {
                every { it(any()) } returns
                    flowOf(ApiResult.Success(emptyList()))
            },
            notificationManager = notificationManager,
            dispatchers = testDispatchers
        )

        vm.openNewReminder()

        val state = vm.uiState.value
        assertTrue("Expected ReminderEditor but got $state", state is ReminderUiState.ReminderEditor)
        val editor = state as ReminderUiState.ReminderEditor
        assertTrue(editor.isNew)
        verify { notificationManager.canScheduleExactAlarms() }
    }

    @Test
    fun `saveReminder with blank title sets titleError without calling use case`() = runTest {
        val createReminder = mockk<CreateReminderUseCase>()
        val getReminders = mockk<GetRemindersUseCase>()
        every { getReminders() } returns flowOf(ApiResult.Success(emptyList()))

        val notificationManager = mockk<ReminderNotificationManager>(relaxed = true)
        every { notificationManager.canScheduleExactAlarms() } returns true

        val vm = ReminderViewModel(
            getRemindersUseCase = getReminders,
            createReminderUseCase = createReminder,
            updateReminderUseCase = mockk<UpdateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeReminder())
            },
            deleteReminderUseCase = mockk<DeleteReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            suggestReminderUseCase = mockk<SuggestReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeReminder())
            },
            getTodosUseCase = mockk<GetTodosUseCase>().also {
                every { it(any()) } returns
                    flowOf(ApiResult.Success(emptyList()))
            },
            notificationManager = notificationManager,
            dispatchers = testDispatchers
        )

        vm.openNewReminder()
        // Title is blank by default in a new reminder — just call saveReminder directly
        vm.saveReminder()

        val state = vm.uiState.value
        assertTrue("Expected ReminderEditor but got $state", state is ReminderUiState.ReminderEditor)
        val editor = state as ReminderUiState.ReminderEditor
        assertNotNull("Expected titleError to be set", editor.titleError)
        coVerify(exactly = 0) { createReminder(any()) }
    }

    @Test
    fun `saveReminder with past triggerTime sets triggerTimeError`() = runTest {
        val notificationManager = mockk<ReminderNotificationManager>(relaxed = true)
        every { notificationManager.canScheduleExactAlarms() } returns true

        val getReminders = mockk<GetRemindersUseCase>()
        every { getReminders() } returns flowOf(ApiResult.Success(emptyList()))

        val vm = ReminderViewModel(
            getRemindersUseCase = getReminders,
            createReminderUseCase = mockk<CreateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeReminder())
            },
            updateReminderUseCase = mockk<UpdateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeReminder())
            },
            deleteReminderUseCase = mockk<DeleteReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            suggestReminderUseCase = mockk<SuggestReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeReminder())
            },
            getTodosUseCase = mockk<GetTodosUseCase>().also {
                every { it(any()) } returns
                    flowOf(ApiResult.Success(emptyList()))
            },
            notificationManager = notificationManager,
            dispatchers = testDispatchers
        )

        // Open editor with a reminder with past trigger time and non-blank title
        val pastReminder = makeReminder().copy(title = "Past Reminder", triggerTime = 1L) // epoch 1ms = past
        vm.openEditReminder(pastReminder)
        vm.saveReminder()

        val state = vm.uiState.value
        assertTrue("Expected ReminderEditor but got $state", state is ReminderUiState.ReminderEditor)
        val editor = state as ReminderUiState.ReminderEditor
        assertNotNull("Expected triggerTimeError to be set", editor.triggerTimeError)
    }

    @Test
    fun `saveReminder with valid data and isNew=true calls createReminderUseCase then scheduleAlarm`() = runTest {
        val reminder = makeReminder(title = "Valid Reminder")
        val createReminder = mockk<CreateReminderUseCase>()
        coEvery { createReminder(any()) } returns ApiResult.Success(reminder)

        val notificationManager = mockk<ReminderNotificationManager>(relaxed = true)
        every { notificationManager.canScheduleExactAlarms() } returns true

        val getReminders = mockk<GetRemindersUseCase>()
        every { getReminders() } returns flowOf(ApiResult.Success(emptyList()))

        val vm = ReminderViewModel(
            getRemindersUseCase = getReminders,
            createReminderUseCase = createReminder,
            updateReminderUseCase = mockk<UpdateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(reminder)
            },
            deleteReminderUseCase = mockk<DeleteReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            suggestReminderUseCase = mockk<SuggestReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(reminder)
            },
            getTodosUseCase = mockk<GetTodosUseCase>().also {
                every { it(any()) } returns
                    flowOf(ApiResult.Success(emptyList()))
            },
            notificationManager = notificationManager,
            dispatchers = testDispatchers
        )

        vm.openEditReminder(reminder.copy(id = "new-r", title = "Valid"))
        // Set it as new
        vm.openNewReminder()
        // Update draft with valid title and future trigger time
        vm.updateDraft(
            title = "Valid Reminder",
            triggerTime = System.currentTimeMillis() + 3_600_000L,
            recurrenceRule = null,
            linkedTodoId = null
        )
        vm.saveReminder()

        coVerify { createReminder(any()) }
        verify { notificationManager.scheduleAlarm(any()) }
    }

    @Test
    fun `saveReminder update calls cancelAlarm then update then scheduleAlarm`() =
        runTest {
            val reminder = makeReminder(title = "Existing Reminder")
            val updateReminder = mockk<UpdateReminderUseCase>()
            coEvery { updateReminder(any()) } returns ApiResult.Success(reminder)

            val notificationManager = mockk<ReminderNotificationManager>(relaxed = true)
            every { notificationManager.canScheduleExactAlarms() } returns true

            val getReminders = mockk<GetRemindersUseCase>()
            every { getReminders() } returns flowOf(ApiResult.Success(emptyList()))

            val vm = ReminderViewModel(
                getRemindersUseCase = getReminders,
                createReminderUseCase = mockk<CreateReminderUseCase>().also {
                    coEvery { it(any()) } returns
                        ApiResult.Success(reminder)
                },
                updateReminderUseCase = updateReminder,
                deleteReminderUseCase = mockk<DeleteReminderUseCase>().also {
                    coEvery { it(any()) } returns
                        ApiResult.Success(Unit)
                },
                suggestReminderUseCase = mockk<SuggestReminderUseCase>().also {
                    coEvery { it(any()) } returns
                        ApiResult.Success(reminder)
                },
                getTodosUseCase = mockk<GetTodosUseCase>().also {
                    every { it(any()) } returns
                        flowOf(ApiResult.Success(emptyList()))
                },
                notificationManager = notificationManager,
                dispatchers = testDispatchers
            )

            vm.openEditReminder(reminder)
            vm.saveReminder()

            coVerify { updateReminder(any()) }
            verify { notificationManager.cancelAlarm(reminder.id, reminder.title) }
            verify { notificationManager.scheduleAlarm(any()) }
        }

    @Test
    fun `deleteReminder calls cancelAlarm and deleteReminderUseCase and optimistically removes from list`() = runTest {
        val reminder = makeReminder()
        val deleteReminder = mockk<DeleteReminderUseCase>()
        coEvery { deleteReminder(any()) } returns ApiResult.Success(Unit)

        val notificationManager = mockk<ReminderNotificationManager>(relaxed = true)
        every { notificationManager.canScheduleExactAlarms() } returns true

        val getReminders = mockk<GetRemindersUseCase>()
        every { getReminders() } returns flowOf(ApiResult.Success(listOf(reminder)))

        val vm = ReminderViewModel(
            getRemindersUseCase = getReminders,
            createReminderUseCase = mockk<CreateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(reminder)
            },
            updateReminderUseCase = mockk<UpdateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(reminder)
            },
            deleteReminderUseCase = deleteReminder,
            suggestReminderUseCase = mockk<SuggestReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(reminder)
            },
            getTodosUseCase = mockk<GetTodosUseCase>().also {
                every { it(any()) } returns
                    flowOf(ApiResult.Success(emptyList()))
            },
            notificationManager = notificationManager,
            dispatchers = testDispatchers
        )

        vm.deleteReminder(reminder.id)

        verify { notificationManager.cancelAlarm(reminder.id, reminder.title) }
        coVerify { deleteReminder(reminder.id) }

        val state = vm.uiState.value as ReminderUiState.ReminderList
        assertTrue(state.reminders.none { it.id == reminder.id })
        assertEquals(reminder, state.deletedReminder)
    }

    @Test
    fun `undoDelete calls createReminderUseCase and scheduleAlarm`() = runTest {
        val reminder = makeReminder()
        val createReminder = mockk<CreateReminderUseCase>()
        coEvery { createReminder(any()) } returns ApiResult.Success(reminder)

        val notificationManager = mockk<ReminderNotificationManager>(relaxed = true)
        every { notificationManager.canScheduleExactAlarms() } returns true

        val getReminders = mockk<GetRemindersUseCase>()
        every { getReminders() } returns flowOf(ApiResult.Success(listOf(reminder)))

        val vm = ReminderViewModel(
            getRemindersUseCase = getReminders,
            createReminderUseCase = createReminder,
            updateReminderUseCase = mockk<UpdateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(reminder)
            },
            deleteReminderUseCase = mockk<DeleteReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            suggestReminderUseCase = mockk<SuggestReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(reminder)
            },
            getTodosUseCase = mockk<GetTodosUseCase>().also {
                every { it(any()) } returns
                    flowOf(ApiResult.Success(emptyList()))
            },
            notificationManager = notificationManager,
            dispatchers = testDispatchers
        )

        vm.deleteReminder(reminder.id)
        vm.undoDelete()

        coVerify { createReminder(any()) }
        verify { notificationManager.scheduleAlarm(any()) }
    }

    @Test
    fun `suggestReminder transitions through AiSuggesting then to ReminderEditor on success`() = runTest {
        val suggested = makeReminder(title = "AI Reminder")
        val suggestReminder = mockk<SuggestReminderUseCase>()

        // We need to capture state mid-flight, but with UnconfinedTestDispatcher
        // the coroutine runs synchronously. We verify the final state is ReminderEditor.
        coEvery { suggestReminder(any()) } returns ApiResult.Success(suggested)

        val getReminders = mockk<GetRemindersUseCase>()
        every { getReminders() } returns flowOf(ApiResult.Success(emptyList()))

        val notificationManager = mockk<ReminderNotificationManager>(relaxed = true)
        every { notificationManager.canScheduleExactAlarms() } returns true

        val vm = ReminderViewModel(
            getRemindersUseCase = getReminders,
            createReminderUseCase = mockk<CreateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(suggested)
            },
            updateReminderUseCase = mockk<UpdateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(suggested)
            },
            deleteReminderUseCase = mockk<DeleteReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            suggestReminderUseCase = suggestReminder,
            getTodosUseCase = mockk<GetTodosUseCase>().also {
                every { it(any()) } returns
                    flowOf(ApiResult.Success(emptyList()))
            },
            notificationManager = notificationManager,
            dispatchers = testDispatchers
        )

        vm.suggestReminder("remind me tomorrow")

        // After completion, state should be ReminderEditor pre-populated with the suggestion
        val state = vm.uiState.value
        assertTrue("Expected ReminderEditor but got $state", state is ReminderUiState.ReminderEditor)
        val editor = state as ReminderUiState.ReminderEditor
        assertTrue(editor.isNew)
        assertEquals(suggested.title, editor.reminder.title)
    }

    @Test
    fun `suggestReminder emits Error on use case failure`() = runTest {
        val suggestReminder = mockk<SuggestReminderUseCase>()
        coEvery { suggestReminder(any()) } returns networkError()

        val getReminders = mockk<GetRemindersUseCase>()
        every { getReminders() } returns flowOf(ApiResult.Success(emptyList()))

        val vm = ReminderViewModel(
            getRemindersUseCase = getReminders,
            createReminderUseCase = mockk<CreateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeReminder())
            },
            updateReminderUseCase = mockk<UpdateReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeReminder())
            },
            deleteReminderUseCase = mockk<DeleteReminderUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            suggestReminderUseCase = suggestReminder,
            getTodosUseCase = mockk<GetTodosUseCase>().also {
                every { it(any()) } returns
                    flowOf(ApiResult.Success(emptyList()))
            },
            notificationManager = mockk<ReminderNotificationManager>(relaxed = true).also {
                every { it.canScheduleExactAlarms() } returns true
            },
            dispatchers = testDispatchers
        )

        vm.suggestReminder("remind me tomorrow")

        assertTrue("Expected Error but got ${vm.uiState.value}", vm.uiState.value is ReminderUiState.Error)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. CalendarViewModel
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildCalendarViewModel(
        getEventsResult: ApiResult<List<CalendarEvent>> = ApiResult.Success(emptyList()),
        createEventResult: ApiResult<CalendarEvent> = ApiResult.Success(makeCalendarEvent()),
        deleteEventResult: ApiResult<Unit> = ApiResult.Success(Unit)
    ): CalendarViewModel {
        val getEvents = mockk<GetCalendarEventsUseCase>()
        every { getEvents(any()) } returns flowOf(getEventsResult)

        val createEvent = mockk<CreateCalendarEventUseCase>()
        coEvery { createEvent(any()) } returns createEventResult

        val deleteEvent = mockk<DeleteCalendarEventUseCase>()
        coEvery { deleteEvent(any()) } returns deleteEventResult

        return CalendarViewModel(
            getCalendarEventsUseCase = getEvents,
            createCalendarEventUseCase = createEvent,
            deleteCalendarEventUseCase = deleteEvent,
            dispatchers = testDispatchers
        )
    }

    @Test
    fun `loadEvents emits CalendarView with events on success`() = runTest {
        val events = listOf(makeCalendarEvent())
        val vm = buildCalendarViewModel(getEventsResult = ApiResult.Success(events))
        val state = vm.uiState.value
        assertTrue("Expected CalendarView but got $state", state is CalendarUiState.CalendarView)
        assertEquals(events, (state as CalendarUiState.CalendarView).events)
    }

    @Test
    fun `loadEvents emits Error on use case failure`() = runTest {
        val vm = buildCalendarViewModel(getEventsResult = networkError())
        val state = vm.uiState.value
        assertTrue("Expected Error but got $state", state is CalendarUiState.Error)
    }

    @Test
    fun `openNewEvent transitions to EventEditor with isNew=true`() = runTest {
        val vm = buildCalendarViewModel()
        vm.openNewEvent()
        val state = vm.uiState.value
        assertTrue("Expected EventEditor but got $state", state is CalendarUiState.EventEditor)
        assertTrue((state as CalendarUiState.EventEditor).isNew)
    }

    @Test
    fun `saveEvent with blank title sets titleError`() = runTest {
        val createEvent = mockk<CreateCalendarEventUseCase>()
        val getEvents = mockk<GetCalendarEventsUseCase>()
        every { getEvents(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val vm = CalendarViewModel(
            getCalendarEventsUseCase = getEvents,
            createCalendarEventUseCase = createEvent,
            deleteCalendarEventUseCase = mockk<DeleteCalendarEventUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            dispatchers = testDispatchers
        )

        vm.openNewEvent()
        // Title is blank by default
        vm.saveEvent()

        val state = vm.uiState.value
        assertTrue("Expected EventEditor but got $state", state is CalendarUiState.EventEditor)
        assertNotNull((state as CalendarUiState.EventEditor).titleError)
        coVerify(exactly = 0) { createEvent(any()) }
    }

    @Test
    fun `saveEvent with endTime before startTime sets endTimeError for non-all-day event`() = runTest {
        val createEvent = mockk<CreateCalendarEventUseCase>()
        val getEvents = mockk<GetCalendarEventsUseCase>()
        every { getEvents(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val vm = CalendarViewModel(
            getCalendarEventsUseCase = getEvents,
            createCalendarEventUseCase = createEvent,
            deleteCalendarEventUseCase = mockk<DeleteCalendarEventUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            dispatchers = testDispatchers
        )

        val now = System.currentTimeMillis()
        vm.openNewEvent(now)
        vm.updateDraft(
            title = "Valid Title",
            description = "",
            startTime = now + 7_200_000L, // start after end
            endTime = now + 3_600_000L, // end before start
            location = null,
            isAllDay = false
        )
        vm.saveEvent()

        val state = vm.uiState.value
        assertTrue("Expected EventEditor but got $state", state is CalendarUiState.EventEditor)
        assertNotNull((state as CalendarUiState.EventEditor).endTimeError)
        coVerify(exactly = 0) { createEvent(any()) }
    }

    @Test
    fun `saveEvent with valid data calls createCalendarEventUseCase`() = runTest {
        val event = makeCalendarEvent(title = "Valid Event")
        val createEvent = mockk<CreateCalendarEventUseCase>()
        coEvery { createEvent(any()) } returns ApiResult.Success(event)

        val getEvents = mockk<GetCalendarEventsUseCase>()
        every { getEvents(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val vm = CalendarViewModel(
            getCalendarEventsUseCase = getEvents,
            createCalendarEventUseCase = createEvent,
            deleteCalendarEventUseCase = mockk<DeleteCalendarEventUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            dispatchers = testDispatchers
        )

        val now = System.currentTimeMillis()
        vm.openNewEvent(now)
        vm.updateDraft(
            title = "Valid Event",
            description = "",
            startTime = now + 3_600_000L,
            endTime = now + 7_200_000L,
            location = null,
            isAllDay = false
        )
        vm.saveEvent()

        coVerify { createEvent(any()) }
        assertTrue(vm.uiState.value is CalendarUiState.CalendarView)
    }

    @Test
    fun `deleteEvent calls deleteCalendarEventUseCase then reloads`() = runTest {
        val deleteEvent = mockk<DeleteCalendarEventUseCase>()
        coEvery { deleteEvent(any()) } returns ApiResult.Success(Unit)

        val getEvents = mockk<GetCalendarEventsUseCase>()
        every { getEvents(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val vm = CalendarViewModel(
            getCalendarEventsUseCase = getEvents,
            createCalendarEventUseCase = mockk<CreateCalendarEventUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeCalendarEvent())
            },
            deleteCalendarEventUseCase = deleteEvent,
            dispatchers = testDispatchers
        )

        vm.deleteEvent("e1")

        coVerify { deleteEvent("e1") }
        assertTrue(vm.uiState.value is CalendarUiState.CalendarView)
    }

    @Test
    fun `switchViewMode updates viewMode in state`() = runTest {
        val getEvents = mockk<GetCalendarEventsUseCase>()
        every { getEvents(any()) } returns flowOf(ApiResult.Success(emptyList()))

        val vm = CalendarViewModel(
            getCalendarEventsUseCase = getEvents,
            createCalendarEventUseCase = mockk<CreateCalendarEventUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeCalendarEvent())
            },
            deleteCalendarEventUseCase = mockk<DeleteCalendarEventUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            dispatchers = testDispatchers
        )

        vm.switchViewMode(CalendarViewMode.WEEKLY)

        val state = vm.uiState.value
        assertTrue("Expected CalendarView but got $state", state is CalendarUiState.CalendarView)
        assertEquals(CalendarViewMode.WEEKLY, (state as CalendarUiState.CalendarView).viewMode)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. HabitViewModel
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildHabitViewModel(
        getHabitsResult: ApiResult<List<HabitDefinition>> = ApiResult.Success(emptyList()),
        getHabitEntriesResult: ApiResult<List<HabitEntry>> = ApiResult.Success(emptyList()),
        createHabitResult: ApiResult<HabitDefinition> = ApiResult.Success(makeHabit()),
        deleteHabitResult: ApiResult<Unit> = ApiResult.Success(Unit),
        logEntryResult: ApiResult<HabitEntry> = ApiResult.Success(makeHabitEntry()),
        insightsResult: ApiResult<String> = ApiResult.Success("Great streak!")
    ): HabitViewModel {
        val repository = mockk<ProductivityRepository>()
        every { repository.getHabits() } returns flowOf(getHabitsResult)
        every { repository.getHabitEntries(any()) } returns flowOf(getHabitEntriesResult)
        coEvery { repository.createHabit(any()) } returns createHabitResult

        val createHabit = mockk<CreateHabitUseCase>()
        coEvery { createHabit(any()) } returns createHabitResult

        val deleteHabit = mockk<DeleteHabitUseCase>()
        coEvery { deleteHabit(any()) } returns deleteHabitResult

        val logEntry = mockk<LogHabitEntryUseCase>()
        coEvery { logEntry(any()) } returns logEntryResult

        val getInsights = mockk<GetHabitInsightsUseCase>()
        coEvery { getInsights(any()) } returns insightsResult

        return HabitViewModel(
            createHabitUseCase = createHabit,
            deleteHabitUseCase = deleteHabit,
            logHabitEntryUseCase = logEntry,
            getHabitInsightsUseCase = getInsights,
            productivityRepository = repository,
            dispatchers = testDispatchers
        )
    }

    @Test
    fun `loadHabits emits HabitList with habitEntriesMap populated`() = runTest {
        val habit = makeHabit()
        val entry = makeHabitEntry(habitId = habit.id)

        val repository = mockk<ProductivityRepository>()
        every { repository.getHabits() } returns flowOf(ApiResult.Success(listOf(habit)))
        every { repository.getHabitEntries(habit.id) } returns flowOf(ApiResult.Success(listOf(entry)))
        coEvery { repository.createHabit(any()) } returns ApiResult.Success(habit)

        val vm = HabitViewModel(
            createHabitUseCase = mockk<CreateHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(habit)
            },
            deleteHabitUseCase = mockk<DeleteHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            logHabitEntryUseCase = mockk<LogHabitEntryUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(entry)
            },
            getHabitInsightsUseCase = mockk<GetHabitInsightsUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success("insights")
            },
            productivityRepository = repository,
            dispatchers = testDispatchers
        )

        val state = vm.uiState.value
        assertTrue("Expected HabitList but got $state", state is HabitUiState.HabitList)
        val list = state as HabitUiState.HabitList
        assertEquals(listOf(habit), list.habits)
        assertEquals(listOf(entry), list.habitEntriesMap[habit.id])
    }

    @Test
    fun `loadHabits emits Error on failure`() = runTest {
        val vm = buildHabitViewModel(getHabitsResult = networkError())
        val state = vm.uiState.value
        assertTrue("Expected Error but got $state", state is HabitUiState.Error)
    }

    @Test
    fun `openNewHabit transitions to HabitEditor with isNew=true`() = runTest {
        val vm = buildHabitViewModel()
        vm.openNewHabit()
        val state = vm.uiState.value
        assertTrue("Expected HabitEditor but got $state", state is HabitUiState.HabitEditor)
        assertTrue((state as HabitUiState.HabitEditor).isNew)
    }

    @Test
    fun `saveHabit with blank name sets nameError without calling use case`() = runTest {
        val createHabit = mockk<CreateHabitUseCase>()
        val repository = mockk<ProductivityRepository>()
        every { repository.getHabits() } returns flowOf(ApiResult.Success(emptyList()))
        every { repository.getHabitEntries(any()) } returns flowOf(ApiResult.Success(emptyList()))
        coEvery { repository.createHabit(any()) } returns ApiResult.Success(makeHabit())

        val vm = HabitViewModel(
            createHabitUseCase = createHabit,
            deleteHabitUseCase = mockk<DeleteHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            logHabitEntryUseCase = mockk<LogHabitEntryUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeHabitEntry())
            },
            getHabitInsightsUseCase = mockk<GetHabitInsightsUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success("insights")
            },
            productivityRepository = repository,
            dispatchers = testDispatchers
        )

        vm.openNewHabit()
        // Name is blank by default in a new habit
        vm.saveHabit()

        val state = vm.uiState.value
        assertTrue("Expected HabitEditor but got $state", state is HabitUiState.HabitEditor)
        assertNotNull((state as HabitUiState.HabitEditor).nameError)
        coVerify(exactly = 0) { createHabit(any()) }
    }

    @Test
    fun `saveHabit with valid name and isNew=true calls createHabitUseCase then reloads`() = runTest {
        val habit = makeHabit(name = "Exercise")
        val createHabit = mockk<CreateHabitUseCase>()
        coEvery { createHabit(any()) } returns ApiResult.Success(habit)

        val repository = mockk<ProductivityRepository>()
        every { repository.getHabits() } returns flowOf(ApiResult.Success(listOf(habit)))
        every { repository.getHabitEntries(any()) } returns flowOf(ApiResult.Success(emptyList()))
        coEvery { repository.createHabit(any()) } returns ApiResult.Success(habit)

        val vm = HabitViewModel(
            createHabitUseCase = createHabit,
            deleteHabitUseCase = mockk<DeleteHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            logHabitEntryUseCase = mockk<LogHabitEntryUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeHabitEntry())
            },
            getHabitInsightsUseCase = mockk<GetHabitInsightsUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success("insights")
            },
            productivityRepository = repository,
            dispatchers = testDispatchers
        )

        vm.openNewHabit()
        vm.updateDraft(name = "Exercise", description = "", recurrence = HabitRecurrence.DAILY, targetFrequency = 1)
        vm.saveHabit()

        coVerify { createHabit(any()) }
        assertTrue(vm.uiState.value is HabitUiState.HabitList)
    }

    @Test
    fun `saveHabit with valid name and isNew=false calls productivityRepository createHabit`() = runTest {
        val habit = makeHabit(name = "Meditate")
        val repository = mockk<ProductivityRepository>()
        every { repository.getHabits() } returns flowOf(ApiResult.Success(listOf(habit)))
        every { repository.getHabitEntries(any()) } returns flowOf(ApiResult.Success(emptyList()))
        coEvery { repository.createHabit(any()) } returns ApiResult.Success(habit)

        val vm = HabitViewModel(
            createHabitUseCase = mockk<CreateHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(habit)
            },
            deleteHabitUseCase = mockk<DeleteHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            logHabitEntryUseCase = mockk<LogHabitEntryUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeHabitEntry())
            },
            getHabitInsightsUseCase = mockk<GetHabitInsightsUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success("insights")
            },
            productivityRepository = repository,
            dispatchers = testDispatchers
        )

        vm.openEditHabit(habit)
        vm.saveHabit()

        coVerify { repository.createHabit(any()) }
        assertTrue(vm.uiState.value is HabitUiState.HabitList)
    }

    @Test
    fun `deleteHabit calls deleteHabitUseCase and reloads`() = runTest {
        val habit = makeHabit()
        val deleteHabit = mockk<DeleteHabitUseCase>()
        coEvery { deleteHabit(any()) } returns ApiResult.Success(Unit)

        val repository = mockk<ProductivityRepository>()
        every { repository.getHabits() } returns flowOf(ApiResult.Success(listOf(habit)))
        every { repository.getHabitEntries(any()) } returns flowOf(ApiResult.Success(emptyList()))
        coEvery { repository.createHabit(any()) } returns ApiResult.Success(habit)

        val vm = HabitViewModel(
            createHabitUseCase = mockk<CreateHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(habit)
            },
            deleteHabitUseCase = deleteHabit,
            logHabitEntryUseCase = mockk<LogHabitEntryUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeHabitEntry())
            },
            getHabitInsightsUseCase = mockk<GetHabitInsightsUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success("insights")
            },
            productivityRepository = repository,
            dispatchers = testDispatchers
        )

        vm.deleteHabit(habit.id)

        coVerify { deleteHabit(habit.id) }
        assertTrue(vm.uiState.value is HabitUiState.HabitList)
    }

    @Test
    fun `logCompletion calls logHabitEntryUseCase and optimistically updates habitEntriesMap`() = runTest {
        val habit = makeHabit()
        val entry = makeHabitEntry(habitId = habit.id)
        val logEntry = mockk<LogHabitEntryUseCase>()
        coEvery { logEntry(any()) } returns ApiResult.Success(entry)

        val repository = mockk<ProductivityRepository>()
        every { repository.getHabits() } returns flowOf(ApiResult.Success(listOf(habit)))
        every { repository.getHabitEntries(habit.id) } returns flowOf(ApiResult.Success(emptyList()))
        coEvery { repository.createHabit(any()) } returns ApiResult.Success(habit)

        val vm = HabitViewModel(
            createHabitUseCase = mockk<CreateHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(habit)
            },
            deleteHabitUseCase = mockk<DeleteHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            logHabitEntryUseCase = logEntry,
            getHabitInsightsUseCase = mockk<GetHabitInsightsUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success("insights")
            },
            productivityRepository = repository,
            dispatchers = testDispatchers
        )

        vm.logCompletion(habit.id)

        coVerify { logEntry(any()) }
        val state = vm.uiState.value as HabitUiState.HabitList
        val entries = state.habitEntriesMap[habit.id]
        assertNotNull(entries)
        assertTrue(entries!!.any { it.id == entry.id })
    }

    @Test
    fun `openInsights transitions to HabitInsights loading then with insightsText on success`() = runTest {
        val habit = makeHabit()
        val insightsText = "You have a great 7-day streak!"
        val getInsights = mockk<GetHabitInsightsUseCase>()
        coEvery { getInsights(habit.id) } returns ApiResult.Success(insightsText)

        val repository = mockk<ProductivityRepository>()
        every { repository.getHabits() } returns flowOf(ApiResult.Success(listOf(habit)))
        every { repository.getHabitEntries(any()) } returns flowOf(ApiResult.Success(emptyList()))
        coEvery { repository.createHabit(any()) } returns ApiResult.Success(habit)

        val vm = HabitViewModel(
            createHabitUseCase = mockk<CreateHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(habit)
            },
            deleteHabitUseCase = mockk<DeleteHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            logHabitEntryUseCase = mockk<LogHabitEntryUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeHabitEntry())
            },
            getHabitInsightsUseCase = getInsights,
            productivityRepository = repository,
            dispatchers = testDispatchers
        )

        vm.openInsights(habit)

        // With UnconfinedTestDispatcher the coroutine completes synchronously, final state is HabitInsights with text
        val state = vm.uiState.value
        assertTrue("Expected HabitInsights but got $state", state is HabitUiState.HabitInsights)
        val insights = state as HabitUiState.HabitInsights
        assertEquals(insightsText, insights.insightsText)
        assertEquals(false, insights.isLoading)
    }

    @Test
    fun `openInsights emits Error on use case failure`() = runTest {
        val habit = makeHabit()
        val getInsights = mockk<GetHabitInsightsUseCase>()
        coEvery { getInsights(any()) } returns networkError()

        val repository = mockk<ProductivityRepository>()
        every { repository.getHabits() } returns flowOf(ApiResult.Success(listOf(habit)))
        every { repository.getHabitEntries(any()) } returns flowOf(ApiResult.Success(emptyList()))
        coEvery { repository.createHabit(any()) } returns ApiResult.Success(habit)

        val vm = HabitViewModel(
            createHabitUseCase = mockk<CreateHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(habit)
            },
            deleteHabitUseCase = mockk<DeleteHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            logHabitEntryUseCase = mockk<LogHabitEntryUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeHabitEntry())
            },
            getHabitInsightsUseCase = getInsights,
            productivityRepository = repository,
            dispatchers = testDispatchers
        )

        vm.openInsights(habit)

        assertTrue("Expected Error but got ${vm.uiState.value}", vm.uiState.value is HabitUiState.Error)
    }

    @Test
    fun `openInsights emits Error with network message on NetworkUnavailable`() = runTest {
        val habit = makeHabit()
        val getInsights = mockk<GetHabitInsightsUseCase>()
        coEvery { getInsights(any()) } returns ApiResult.NetworkUnavailable

        val repository = mockk<ProductivityRepository>()
        every { repository.getHabits() } returns flowOf(ApiResult.Success(listOf(habit)))
        every { repository.getHabitEntries(any()) } returns flowOf(ApiResult.Success(emptyList()))
        coEvery { repository.createHabit(any()) } returns ApiResult.Success(habit)

        val vm = HabitViewModel(
            createHabitUseCase = mockk<CreateHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(habit)
            },
            deleteHabitUseCase = mockk<DeleteHabitUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(Unit)
            },
            logHabitEntryUseCase = mockk<LogHabitEntryUseCase>().also {
                coEvery { it(any()) } returns
                    ApiResult.Success(makeHabitEntry())
            },
            getHabitInsightsUseCase = getInsights,
            productivityRepository = repository,
            dispatchers = testDispatchers
        )

        vm.openInsights(habit)

        val state = vm.uiState.value
        assertTrue("Expected Error but got $state", state is HabitUiState.Error)
        val errorMessage = (state as HabitUiState.Error).message
        assertTrue("Expected network error message", errorMessage.contains("network", ignoreCase = true))
    }
}

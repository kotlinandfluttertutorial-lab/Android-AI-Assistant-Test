/**
 * ProductivityUseCaseTest.kt — domain module unit tests
 *
 * Tests for productivity use cases grouped by sub-feature:
 *   - Todo: CreateTodo, UpdateTodo, DeleteTodo, GetTodos, GenerateTodosFromPrompt
 *   - Calendar: CreateCalendarEvent, GetCalendarEvents, DeleteCalendarEvent
 *   - Reminders: CreateReminder, UpdateReminder, DeleteReminder, GetReminders, SuggestReminder
 *   - Habits: CreateHabit, LogHabitEntry, GetHabitInsights, DeleteHabit
 *
 * Requirements: 21.1
 * Related requirements: 13.1, 16.3, 16.4, 19.1
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK for ProductivityRepository
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.model.CalendarEventSource
import com.aiassistant.domain.model.HabitDefinition
import com.aiassistant.domain.model.HabitEntry
import com.aiassistant.domain.model.HabitRecurrence
import com.aiassistant.domain.model.Priority
import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.model.TodoItem
import com.aiassistant.domain.repository.DateRange
import com.aiassistant.domain.repository.ProductivityRepository
import com.aiassistant.domain.repository.TodoFilter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private val NOW = System.currentTimeMillis()

private val SAMPLE_TODO = TodoItem(
    id = "todo-001",
    userId = "user-1",
    title = "Buy groceries",
    priority = Priority.MEDIUM,
    syncStatus = SyncStatus.PENDING,
    createdAt = NOW,
    updatedAt = NOW
)

private val SAMPLE_EVENT = CalendarEvent(
    id = "event-001", userId = "user-1", title = "Team standup",
    startTime = NOW, endTime = NOW + 3_600_000L,
    source = CalendarEventSource.LOCAL, syncStatus = SyncStatus.PENDING,
    createdAt = NOW, updatedAt = NOW
)

private val SAMPLE_REMINDER = Reminder(
    id = "rem-001",
    userId = "user-1",
    title = "Review PR",
    triggerTime = NOW + 7_200_000L,
    syncStatus = SyncStatus.PENDING,
    createdAt = NOW,
    updatedAt = NOW
)

private val SAMPLE_HABIT = HabitDefinition(
    id = "habit-001",
    userId = "user-1",
    name = "Morning run",
    recurrence = HabitRecurrence.DAILY,
    targetFrequency = 1,
    createdAt = NOW,
    updatedAt = NOW
)

private val SAMPLE_ENTRY = HabitEntry(
    id = "entry-001",
    habitId = "habit-001",
    userId = "user-1",
    completedAt = NOW
)

// ─── CreateTodoUseCase ────────────────────────────────────────────────────────

class CreateTodoUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = CreateTodoUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("CreateTodoUseCase") {
            describe("successful creation") {
                it("returns Success with TodoItem on valid input") {
                    coEvery { repository.createTodo(SAMPLE_TODO) } returns ApiResult.Success(SAMPLE_TODO)
                    val result = useCase(SAMPLE_TODO)
                    result.shouldBeInstanceOf<ApiResult.Success<TodoItem>>()
                    (result as ApiResult.Success<TodoItem>).data shouldBe SAMPLE_TODO
                }
                it("delegates to repository exactly once") {
                    coEvery { repository.createTodo(SAMPLE_TODO) } returns ApiResult.Success(SAMPLE_TODO)
                    useCase(SAMPLE_TODO)
                    coVerify(exactly = 1) { repository.createTodo(SAMPLE_TODO) }
                }
            }
            describe("title validation") {
                it("returns ValidationError when title is blank") {
                    val result = useCase(SAMPLE_TODO.copy(title = ""))
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("ValidationError contains 'title' in fields map") {
                    val result = useCase(SAMPLE_TODO.copy(title = ""))
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreateTodoUseCase.FIELD_TITLE
                }
                it("does NOT call repository when title is blank") {
                    useCase(SAMPLE_TODO.copy(title = ""))
                    coVerify(exactly = 0) { repository.createTodo(any()) }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.createTodo(any()) } returns ApiResult.NetworkUnavailable
                    useCase(SAMPLE_TODO).shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.createTodo(any()) } returns ApiResult.Error(error)
                    val result = useCase(SAMPLE_TODO)
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── UpdateTodoUseCase ────────────────────────────────────────────────────────

class UpdateTodoUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = UpdateTodoUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("UpdateTodoUseCase") {
            describe("successful update") {
                it("returns Success with updated TodoItem on valid input") {
                    coEvery { repository.updateTodo(SAMPLE_TODO) } returns ApiResult.Success(SAMPLE_TODO)
                    val result = useCase(SAMPLE_TODO)
                    result.shouldBeInstanceOf<ApiResult.Success<TodoItem>>()
                }
                it("delegates to repository exactly once") {
                    coEvery { repository.updateTodo(SAMPLE_TODO) } returns ApiResult.Success(SAMPLE_TODO)
                    useCase(SAMPLE_TODO)
                    coVerify(exactly = 1) { repository.updateTodo(SAMPLE_TODO) }
                }
            }
            describe("title validation") {
                it("returns ValidationError when title is blank") {
                    val result = useCase(SAMPLE_TODO.copy(title = ""))
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("ValidationError contains 'title' in fields map") {
                    val result = useCase(SAMPLE_TODO.copy(title = ""))
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey UpdateTodoUseCase.FIELD_TITLE
                }
                it("does NOT call repository when title is blank") {
                    useCase(SAMPLE_TODO.copy(title = ""))
                    coVerify(exactly = 0) { repository.updateTodo(any()) }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.updateTodo(any()) } returns ApiResult.NetworkUnavailable
                    useCase(SAMPLE_TODO).shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.updateTodo(any()) } returns ApiResult.Error(error)
                    (useCase(SAMPLE_TODO) as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── DeleteTodoUseCase ────────────────────────────────────────────────────────

class DeleteTodoUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = DeleteTodoUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("DeleteTodoUseCase") {
            describe("successful deletion") {
                it("returns Success with Unit when repository succeeds") {
                    coEvery { repository.deleteTodo("todo-001") } returns ApiResult.Success(Unit)
                    useCase("todo-001").shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }
                it("delegates to repository exactly once with the given todoId") {
                    coEvery { repository.deleteTodo("todo-001") } returns ApiResult.Success(Unit)
                    useCase("todo-001")
                    coVerify(exactly = 1) { repository.deleteTodo("todo-001") }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.deleteTodo(any()) } returns ApiResult.NetworkUnavailable
                    useCase("todo-001").shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.deleteTodo(any()) } returns ApiResult.Error(error)
                    (useCase("todo-001") as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── GetTodosUseCase ──────────────────────────────────────────────────────────

class GetTodosUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = GetTodosUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("GetTodosUseCase") {
            describe("successful retrieval") {
                it("returns Flow emitting Success with list of TodoItems") {
                    val todos = listOf(SAMPLE_TODO)
                    every { repository.getTodos(any()) } returns flowOf(ApiResult.Success(todos))
                    val result = useCase().first()
                    result.shouldBeInstanceOf<ApiResult.Success<List<TodoItem>>>()
                    (result as ApiResult.Success<List<TodoItem>>).data shouldBe todos
                }
                it("delegates to repository exactly once with default filter") {
                    every { repository.getTodos(any()) } returns flowOf(ApiResult.Success(emptyList()))
                    useCase().first()
                    verify(exactly = 1) { repository.getTodos(TodoFilter()) }
                }
                it("passes custom filter to repository") {
                    val filter = TodoFilter(showCompleted = false, priority = Priority.HIGH)
                    every { repository.getTodos(filter) } returns flowOf(ApiResult.Success(emptyList()))
                    useCase(filter).first()
                    verify(exactly = 1) { repository.getTodos(filter) }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository Flow") {
                    every { repository.getTodos(any()) } returns flowOf(ApiResult.NetworkUnavailable)
                    useCase().first().shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
            }
        }
    })

// ─── GenerateTodosFromPromptUseCase ───────────────────────────────────────────

class GenerateTodosFromPromptUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = GenerateTodosFromPromptUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("GenerateTodosFromPromptUseCase") {
            describe("successful generation") {
                it("returns Success with list of suggested TodoItems") {
                    val todos = listOf(SAMPLE_TODO)
                    coEvery { repository.generateTodosFromPrompt("Plan product launch") } returns
                        ApiResult.Success(todos)
                    val result = useCase("Plan product launch")
                    result.shouldBeInstanceOf<ApiResult.Success<List<TodoItem>>>()
                    (result as ApiResult.Success<List<TodoItem>>).data shouldBe todos
                }
                it("delegates to repository with trimmed prompt") {
                    coEvery { repository.generateTodosFromPrompt("Plan product launch") } returns
                        ApiResult.Success(emptyList())
                    useCase("  Plan product launch  ")
                    coVerify(exactly = 1) { repository.generateTodosFromPrompt("Plan product launch") }
                }
            }
            describe("prompt validation") {
                it("returns ValidationError when prompt is blank") {
                    val result = useCase("")
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("ValidationError contains 'prompt' in fields map") {
                    val result = useCase("")
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey GenerateTodosFromPromptUseCase.FIELD_PROMPT
                }
                it("does NOT call repository when prompt is blank") {
                    useCase("")
                    coVerify(exactly = 0) { repository.generateTodosFromPrompt(any()) }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.generateTodosFromPrompt(any()) } returns ApiResult.NetworkUnavailable
                    useCase("some prompt").shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.generateTodosFromPrompt(any()) } returns ApiResult.Error(error)
                    (useCase("some prompt") as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── CreateCalendarEventUseCase ───────────────────────────────────────────────

class CreateCalendarEventUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = CreateCalendarEventUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("CreateCalendarEventUseCase") {
            describe("successful creation") {
                it("returns Success with CalendarEvent on valid input") {
                    coEvery { repository.createCalendarEvent(SAMPLE_EVENT) } returns
                        ApiResult.Success(SAMPLE_EVENT)
                    val result = useCase(SAMPLE_EVENT)
                    result.shouldBeInstanceOf<ApiResult.Success<CalendarEvent>>()
                    (result as ApiResult.Success<CalendarEvent>).data shouldBe SAMPLE_EVENT
                }
                it("delegates to repository exactly once") {
                    coEvery { repository.createCalendarEvent(SAMPLE_EVENT) } returns
                        ApiResult.Success(SAMPLE_EVENT)
                    useCase(SAMPLE_EVENT)
                    coVerify(exactly = 1) { repository.createCalendarEvent(SAMPLE_EVENT) }
                }
                it("accepts all-day event with endTime before startTime") {
                    val allDay = SAMPLE_EVENT.copy(isAllDay = true, endTime = SAMPLE_EVENT.startTime - 1)
                    coEvery { repository.createCalendarEvent(allDay) } returns ApiResult.Success(allDay)
                    useCase(allDay).shouldBeInstanceOf<ApiResult.Success<CalendarEvent>>()
                }
            }
            describe("title validation") {
                it("returns ValidationError when title is blank") {
                    val result = useCase(SAMPLE_EVENT.copy(title = ""))
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("ValidationError contains 'title' in fields map") {
                    val result = useCase(SAMPLE_EVENT.copy(title = ""))
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreateCalendarEventUseCase.FIELD_TITLE
                }
                it("does NOT call repository when title is blank") {
                    useCase(SAMPLE_EVENT.copy(title = ""))
                    coVerify(exactly = 0) { repository.createCalendarEvent(any()) }
                }
            }
            describe("time validation") {
                it("returns ValidationError when endTime < startTime for non-all-day event") {
                    val badEvent = SAMPLE_EVENT.copy(
                        isAllDay = false,
                        startTime = NOW + 3_600_000L,
                        endTime = NOW
                    )
                    val result = useCase(badEvent)
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreateCalendarEventUseCase.FIELD_END_TIME
                }
                it("does NOT call repository when endTime < startTime") {
                    useCase(SAMPLE_EVENT.copy(endTime = SAMPLE_EVENT.startTime - 1, isAllDay = false))
                    coVerify(exactly = 0) { repository.createCalendarEvent(any()) }
                }
                it("title error wins over time error when both are invalid") {
                    val badEvent = SAMPLE_EVENT.copy(title = "", endTime = SAMPLE_EVENT.startTime - 1)
                    val result = useCase(badEvent)
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreateCalendarEventUseCase.FIELD_TITLE
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.createCalendarEvent(any()) } returns ApiResult.NetworkUnavailable
                    useCase(SAMPLE_EVENT).shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.createCalendarEvent(any()) } returns ApiResult.Error(error)
                    (useCase(SAMPLE_EVENT) as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── GetCalendarEventsUseCase ─────────────────────────────────────────────────

class GetCalendarEventsUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = GetCalendarEventsUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("GetCalendarEventsUseCase") {
            describe("successful retrieval") {
                it("returns Flow emitting Success with list of CalendarEvents") {
                    val range = DateRange(start = NOW, end = NOW + 86_400_000L)
                    val events = listOf(SAMPLE_EVENT)
                    every { repository.getCalendarEvents(range) } returns flowOf(ApiResult.Success(events))
                    val result = useCase(range).first()
                    result.shouldBeInstanceOf<ApiResult.Success<List<CalendarEvent>>>()
                    (result as ApiResult.Success<List<CalendarEvent>>).data shouldBe events
                }
                it("delegates to repository exactly once with the given range") {
                    val range = DateRange(start = NOW, end = NOW + 86_400_000L)
                    every { repository.getCalendarEvents(range) } returns flowOf(ApiResult.Success(emptyList()))
                    useCase(range).first()
                    verify(exactly = 1) { repository.getCalendarEvents(range) }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository Flow") {
                    val range = DateRange(start = NOW, end = NOW + 86_400_000L)
                    every { repository.getCalendarEvents(range) } returns flowOf(ApiResult.NetworkUnavailable)
                    useCase(range).first().shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
            }
        }
    })

// ─── DeleteCalendarEventUseCase ───────────────────────────────────────────────

class DeleteCalendarEventUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = DeleteCalendarEventUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("DeleteCalendarEventUseCase") {
            describe("successful deletion") {
                it("returns Success with Unit when repository succeeds") {
                    coEvery { repository.deleteCalendarEvent("event-001") } returns ApiResult.Success(Unit)
                    useCase("event-001").shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }
                it("delegates to repository exactly once with the given eventId") {
                    coEvery { repository.deleteCalendarEvent("event-001") } returns ApiResult.Success(Unit)
                    useCase("event-001")
                    coVerify(exactly = 1) { repository.deleteCalendarEvent("event-001") }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.deleteCalendarEvent(any()) } returns ApiResult.NetworkUnavailable
                    useCase("event-001").shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.deleteCalendarEvent(any()) } returns ApiResult.Error(error)
                    (useCase("event-001") as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── CreateReminderUseCase ────────────────────────────────────────────────────

class CreateReminderUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = CreateReminderUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("CreateReminderUseCase") {
            describe("successful creation") {
                it("returns Success with Reminder on valid input") {
                    coEvery { repository.createReminder(SAMPLE_REMINDER) } returns
                        ApiResult.Success(SAMPLE_REMINDER)
                    val result = useCase(SAMPLE_REMINDER)
                    result.shouldBeInstanceOf<ApiResult.Success<Reminder>>()
                    (result as ApiResult.Success<Reminder>).data shouldBe SAMPLE_REMINDER
                }
                it("delegates to repository exactly once") {
                    coEvery { repository.createReminder(SAMPLE_REMINDER) } returns
                        ApiResult.Success(SAMPLE_REMINDER)
                    useCase(SAMPLE_REMINDER)
                    coVerify(exactly = 1) { repository.createReminder(SAMPLE_REMINDER) }
                }
            }
            describe("title validation") {
                it("returns ValidationError when title is blank") {
                    val result = useCase(SAMPLE_REMINDER.copy(title = ""))
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("ValidationError contains 'title' in fields map") {
                    val result = useCase(SAMPLE_REMINDER.copy(title = ""))
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreateReminderUseCase.FIELD_TITLE
                }
                it("does NOT call repository when title is blank") {
                    useCase(SAMPLE_REMINDER.copy(title = ""))
                    coVerify(exactly = 0) { repository.createReminder(any()) }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.createReminder(any()) } returns ApiResult.NetworkUnavailable
                    useCase(SAMPLE_REMINDER).shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.createReminder(any()) } returns ApiResult.Error(error)
                    (useCase(SAMPLE_REMINDER) as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── UpdateReminderUseCase ────────────────────────────────────────────────────

class UpdateReminderUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = UpdateReminderUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("UpdateReminderUseCase") {
            describe("successful update") {
                it("returns Success with updated Reminder on valid input") {
                    coEvery { repository.updateReminder(SAMPLE_REMINDER) } returns
                        ApiResult.Success(SAMPLE_REMINDER)
                    useCase(SAMPLE_REMINDER).shouldBeInstanceOf<ApiResult.Success<Reminder>>()
                }
                it("delegates to repository exactly once") {
                    coEvery { repository.updateReminder(SAMPLE_REMINDER) } returns
                        ApiResult.Success(SAMPLE_REMINDER)
                    useCase(SAMPLE_REMINDER)
                    coVerify(exactly = 1) { repository.updateReminder(SAMPLE_REMINDER) }
                }
            }
            describe("title validation") {
                it("returns ValidationError when title is blank") {
                    val result = useCase(SAMPLE_REMINDER.copy(title = ""))
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("ValidationError contains 'title' in fields map") {
                    val error = (useCase(SAMPLE_REMINDER.copy(title = "")) as ApiResult.Error)
                        .error as DomainError.ValidationError
                    error.fields shouldContainKey UpdateReminderUseCase.FIELD_TITLE
                }
                it("does NOT call repository when title is blank") {
                    useCase(SAMPLE_REMINDER.copy(title = ""))
                    coVerify(exactly = 0) { repository.updateReminder(any()) }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.updateReminder(any()) } returns ApiResult.NetworkUnavailable
                    useCase(SAMPLE_REMINDER).shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.updateReminder(any()) } returns ApiResult.Error(error)
                    (useCase(SAMPLE_REMINDER) as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── DeleteReminderUseCase ────────────────────────────────────────────────────

class DeleteReminderUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = DeleteReminderUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("DeleteReminderUseCase") {
            describe("successful deletion") {
                it("returns Success with Unit when repository succeeds") {
                    coEvery { repository.deleteReminder("rem-001") } returns ApiResult.Success(Unit)
                    useCase("rem-001").shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }
                it("delegates to repository exactly once with given reminderId") {
                    coEvery { repository.deleteReminder("rem-001") } returns ApiResult.Success(Unit)
                    useCase("rem-001")
                    coVerify(exactly = 1) { repository.deleteReminder("rem-001") }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.deleteReminder(any()) } returns ApiResult.NetworkUnavailable
                    useCase("rem-001").shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
            }
        }
    })

// ─── GetRemindersUseCase ──────────────────────────────────────────────────────

class GetRemindersUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = GetRemindersUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("GetRemindersUseCase") {
            describe("successful retrieval") {
                it("returns Flow emitting Success with list of Reminders") {
                    val reminders = listOf(SAMPLE_REMINDER)
                    every { repository.getReminders() } returns flowOf(ApiResult.Success(reminders))
                    val result = useCase().first()
                    (result as ApiResult.Success<List<Reminder>>).data shouldBe reminders
                }
                it("delegates to repository exactly once") {
                    every { repository.getReminders() } returns flowOf(ApiResult.Success(emptyList()))
                    useCase().first()
                    verify(exactly = 1) { repository.getReminders() }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository Flow") {
                    every { repository.getReminders() } returns flowOf(ApiResult.NetworkUnavailable)
                    useCase().first().shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
            }
        }
    })

// ─── SuggestReminderUseCase ───────────────────────────────────────────────────

class SuggestReminderUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = SuggestReminderUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("SuggestReminderUseCase") {
            describe("successful suggestion") {
                it("returns Success with suggested Reminder") {
                    coEvery { repository.suggestReminder("Review PR before standup") } returns
                        ApiResult.Success(SAMPLE_REMINDER)
                    val result = useCase("Review PR before standup")
                    (result as ApiResult.Success<Reminder>).data shouldBe SAMPLE_REMINDER
                }
                it("trims prompt before passing to repository") {
                    coEvery { repository.suggestReminder("Review PR before standup") } returns
                        ApiResult.Success(SAMPLE_REMINDER)
                    useCase("  Review PR before standup  ")
                    coVerify(exactly = 1) { repository.suggestReminder("Review PR before standup") }
                }
            }
            describe("prompt validation") {
                it("returns ValidationError when prompt is blank") {
                    val result = useCase("")
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("ValidationError contains 'prompt' in fields map") {
                    val error = (useCase("") as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey SuggestReminderUseCase.FIELD_PROMPT
                }
                it("does NOT call repository when prompt is blank") {
                    useCase("")
                    coVerify(exactly = 0) { repository.suggestReminder(any()) }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.suggestReminder(any()) } returns ApiResult.NetworkUnavailable
                    useCase("some prompt").shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.suggestReminder(any()) } returns ApiResult.Error(error)
                    (useCase("some prompt") as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── CreateHabitUseCase ───────────────────────────────────────────────────────

class CreateHabitUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = CreateHabitUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("CreateHabitUseCase") {
            describe("successful creation") {
                it("returns Success with HabitDefinition on valid input") {
                    coEvery { repository.createHabit(SAMPLE_HABIT) } returns ApiResult.Success(SAMPLE_HABIT)
                    val result = useCase(SAMPLE_HABIT)
                    result.shouldBeInstanceOf<ApiResult.Success<HabitDefinition>>()
                    (result as ApiResult.Success<HabitDefinition>).data shouldBe SAMPLE_HABIT
                }
                it("delegates to repository exactly once") {
                    coEvery { repository.createHabit(SAMPLE_HABIT) } returns ApiResult.Success(SAMPLE_HABIT)
                    useCase(SAMPLE_HABIT)
                    coVerify(exactly = 1) { repository.createHabit(SAMPLE_HABIT) }
                }
            }
            describe("name validation") {
                it("returns ValidationError when name is blank") {
                    val result = useCase(SAMPLE_HABIT.copy(name = ""))
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("ValidationError contains 'name' in fields map") {
                    val error = (useCase(SAMPLE_HABIT.copy(name = "")) as ApiResult.Error)
                        .error as DomainError.ValidationError
                    error.fields shouldContainKey CreateHabitUseCase.FIELD_NAME
                }
                it("does NOT call repository when name is blank") {
                    useCase(SAMPLE_HABIT.copy(name = ""))
                    coVerify(exactly = 0) { repository.createHabit(any()) }
                }
            }
            describe("targetFrequency validation") {
                it("returns ValidationError when targetFrequency is 0") {
                    val result = useCase(SAMPLE_HABIT.copy(targetFrequency = 0))
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("returns ValidationError when targetFrequency is negative") {
                    val result = useCase(SAMPLE_HABIT.copy(targetFrequency = -1))
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("ValidationError contains 'targetFrequency' in fields map") {
                    val error = (useCase(SAMPLE_HABIT.copy(targetFrequency = 0)) as ApiResult.Error)
                        .error as DomainError.ValidationError
                    error.fields shouldContainKey CreateHabitUseCase.FIELD_FREQUENCY
                }
                it("does NOT call repository when targetFrequency < 1") {
                    useCase(SAMPLE_HABIT.copy(targetFrequency = 0))
                    coVerify(exactly = 0) { repository.createHabit(any()) }
                }
                it("name error wins over frequency error when both are invalid") {
                    val result = useCase(SAMPLE_HABIT.copy(name = "", targetFrequency = 0))
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreateHabitUseCase.FIELD_NAME
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.createHabit(any()) } returns ApiResult.NetworkUnavailable
                    useCase(SAMPLE_HABIT).shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.createHabit(any()) } returns ApiResult.Error(error)
                    (useCase(SAMPLE_HABIT) as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── LogHabitEntryUseCase ─────────────────────────────────────────────────────

class LogHabitEntryUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = LogHabitEntryUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("LogHabitEntryUseCase") {
            describe("successful log") {
                it("returns Success with HabitEntry on valid input") {
                    coEvery { repository.logHabitEntry(SAMPLE_ENTRY) } returns ApiResult.Success(SAMPLE_ENTRY)
                    val result = useCase(SAMPLE_ENTRY)
                    result.shouldBeInstanceOf<ApiResult.Success<HabitEntry>>()
                    (result as ApiResult.Success<HabitEntry>).data shouldBe SAMPLE_ENTRY
                }
                it("delegates to repository exactly once") {
                    coEvery { repository.logHabitEntry(SAMPLE_ENTRY) } returns ApiResult.Success(SAMPLE_ENTRY)
                    useCase(SAMPLE_ENTRY)
                    coVerify(exactly = 1) { repository.logHabitEntry(SAMPLE_ENTRY) }
                }
            }
            describe("habitId validation") {
                it("returns ValidationError when habitId is blank") {
                    val result = useCase(SAMPLE_ENTRY.copy(habitId = ""))
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("ValidationError contains 'habitId' in fields map") {
                    val error = (useCase(SAMPLE_ENTRY.copy(habitId = "")) as ApiResult.Error)
                        .error as DomainError.ValidationError
                    error.fields shouldContainKey LogHabitEntryUseCase.FIELD_HABIT_ID
                }
                it("does NOT call repository when habitId is blank") {
                    useCase(SAMPLE_ENTRY.copy(habitId = ""))
                    coVerify(exactly = 0) { repository.logHabitEntry(any()) }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.logHabitEntry(any()) } returns ApiResult.NetworkUnavailable
                    useCase(SAMPLE_ENTRY).shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.logHabitEntry(any()) } returns ApiResult.Error(error)
                    (useCase(SAMPLE_ENTRY) as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── GetHabitInsightsUseCase ──────────────────────────────────────────────────

class GetHabitInsightsUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = GetHabitInsightsUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("GetHabitInsightsUseCase") {
            describe("successful retrieval") {
                it("returns Success with insights text when repository succeeds") {
                    coEvery { repository.getHabitInsights("habit-001") } returns
                        ApiResult.Success("You complete this habit 80% of the time.")
                    val result = useCase("habit-001")
                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success<String>).data shouldBe
                        "You complete this habit 80% of the time."
                }
                it("delegates to repository exactly once with the given habitId") {
                    coEvery { repository.getHabitInsights("habit-001") } returns
                        ApiResult.Success("insights")
                    useCase("habit-001")
                    coVerify(exactly = 1) { repository.getHabitInsights("habit-001") }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.getHabitInsights(any()) } returns ApiResult.NetworkUnavailable
                    useCase("habit-001").shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.getHabitInsights(any()) } returns ApiResult.Error(error)
                    (useCase("habit-001") as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── DeleteHabitUseCase ───────────────────────────────────────────────────────

class DeleteHabitUseCaseTest :
    DescribeSpec({
        val repository = mockk<ProductivityRepository>()
        val useCase = DeleteHabitUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("DeleteHabitUseCase") {
            describe("successful deletion") {
                it("returns Success with Unit when repository succeeds") {
                    coEvery { repository.deleteHabit("habit-001") } returns ApiResult.Success(Unit)
                    useCase("habit-001").shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }
                it("delegates to repository exactly once with given habitId") {
                    coEvery { repository.deleteHabit("habit-001") } returns ApiResult.Success(Unit)
                    useCase("habit-001")
                    coVerify(exactly = 1) { repository.deleteHabit("habit-001") }
                }
            }
            describe("error propagation") {
                it("propagates NetworkUnavailable from repository") {
                    coEvery { repository.deleteHabit(any()) } returns ApiResult.NetworkUnavailable
                    useCase("habit-001").shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.deleteHabit(any()) } returns ApiResult.Error(error)
                    (useCase("habit-001") as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

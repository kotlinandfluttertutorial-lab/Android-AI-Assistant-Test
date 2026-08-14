/**
 * ProductivityRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [ProductivityRepositoryImpl], covering:
 *   - createTodo() — local insertion with SyncStatus.PENDING; remote sync when online
 *   - updateTodo() — local update + remote sync
 *   - deleteTodo() — local delete + remote sync
 *   - generateTodosFromPrompt() — online/offline guard
 *   - createCalendarEvent() — local insertion + optional remote sync
 *   - deleteCalendarEvent() — local delete + remote sync
 *   - createReminder() — local insertion + remote sync
 *   - deleteReminder() — local delete + remote sync
 *   - suggestReminder() — online/offline guard
 *   - createHabit() — local insertion + remote sync
 *   - deleteHabit() — local delete + remote sync
 *   - logHabitEntry() — local insertion + remote sync
 *   - getHabitInsights() — online/offline guard
 *
 * Architecture: data module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking all DAOs, RemoteDataSource, ConnectivityObserver, SecureStorage
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 13.1, 16.3, 16.4
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.database.dao.CalendarEventDao
import com.aiassistant.core.database.dao.HabitDefinitionDao
import com.aiassistant.core.database.dao.HabitEntryDao
import com.aiassistant.core.database.dao.ReminderDao
import com.aiassistant.core.database.dao.TodoItemDao
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.remote.productivity.CalendarEventDto
import com.aiassistant.data.remote.productivity.HabitDefinitionDto
import com.aiassistant.data.remote.productivity.ProductivityRemoteDataSource
import com.aiassistant.data.remote.productivity.ReminderDto
import com.aiassistant.data.remote.productivity.TodoItemDto
import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.model.CalendarEventSource
import com.aiassistant.domain.model.HabitDefinition
import com.aiassistant.domain.model.HabitEntry
import com.aiassistant.domain.model.HabitRecurrence
import com.aiassistant.domain.model.Priority
import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.model.TodoItem
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

// ─── Fixtures ─────────────────────────────────────────────────────────────────

private fun fakeTodoItem(
    id: String = "todo-1",
    userId: String = "user-1",
    title: String = "Buy groceries",
    syncStatus: SyncStatus = SyncStatus.SYNCED
) = TodoItem(
    id = id,
    userId = userId,
    title = title,
    description = "At the supermarket",
    isCompleted = false,
    dueDate = null,
    priority = Priority.MEDIUM,
    tags = emptyList(),
    syncStatus = syncStatus,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeTodoItemDto(id: String = "todo-1") = TodoItemDto(
    id = id,
    userId = "user-1",
    title = "Buy groceries",
    description = "At the supermarket",
    isCompleted = false,
    dueDate = null,
    priority = "medium",
    tags = emptyList(),
    syncStatus = "synced",
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeCalendarEvent(id: String = "event-1") = CalendarEvent(
    id = id,
    userId = "user-1",
    title = "Team standup",
    description = "Daily meeting",
    startTime = 1_700_000_000L,
    endTime = 1_700_003_600L,
    location = null,
    isAllDay = false,
    source = CalendarEventSource.LOCAL,
    syncStatus = SyncStatus.SYNCED,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeCalendarEventDto(id: String = "event-1") = CalendarEventDto(
    id = id,
    userId = "user-1",
    title = "Team standup",
    description = "Daily meeting",
    startTime = 1_700_000_000L,
    endTime = 1_700_003_600L,
    location = null,
    isAllDay = false,
    source = "local",
    syncStatus = "synced",
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeReminder(id: String = "rem-1") = Reminder(
    id = id,
    userId = "user-1",
    title = "Review PR",
    triggerTime = 1_700_000_000L,
    recurrenceRule = null,
    linkedTodoId = null,
    isCompleted = false,
    syncStatus = SyncStatus.SYNCED,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeReminderDto(id: String = "rem-1") = ReminderDto(
    id = id,
    userId = "user-1",
    title = "Review PR",
    triggerTime = 1_700_000_000L,
    recurrenceRule = null,
    linkedTodoId = null,
    isCompleted = false,
    syncStatus = "synced",
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeHabit(id: String = "habit-1") = HabitDefinition(
    id = id,
    userId = "user-1",
    name = "Exercise",
    description = "30 minutes cardio",
    recurrence = HabitRecurrence.DAILY,
    targetFrequency = 1,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeHabitDefinitionDto(id: String = "habit-1") = HabitDefinitionDto(
    id = id,
    userId = "user-1",
    name = "Exercise",
    description = "30 minutes cardio",
    recurrence = "daily",
    targetFrequency = 1,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeHabitEntry(habitId: String = "habit-1") = HabitEntry(
    id = "entry-1",
    habitId = habitId,
    userId = "user-1",
    completedAt = 1_700_000_000L,
    note = null
)

// ─── Spec ─────────────────────────────────────────────────────────────────────

class ProductivityRepositoryImplTest :
    DescribeSpec({

        val todoItemDao: TodoItemDao = mockk(relaxed = true)
        val calendarEventDao: CalendarEventDao = mockk(relaxed = true)
        val reminderDao: ReminderDao = mockk(relaxed = true)
        val habitDefinitionDao: HabitDefinitionDao = mockk(relaxed = true)
        val habitEntryDao: HabitEntryDao = mockk(relaxed = true)
        val remoteSource: ProductivityRemoteDataSource = mockk(relaxed = true)
        val connectivityObserver: ConnectivityObserver = mockk()
        val secureStorage: SecureStorage = mockk()
        val dispatchers = TestDispatcherProvider()

        lateinit var repository: ProductivityRepositoryImpl

        beforeEach {
            clearAllMocks()
            every { connectivityObserver.isConnectedFlow } returns flowOf(true)
            every { secureStorage.getJwt() } returns "header.payload.userId"
            repository = ProductivityRepositoryImpl(
                todoItemDao = todoItemDao,
                calendarEventDao = calendarEventDao,
                reminderDao = reminderDao,
                habitDefinitionDao = habitDefinitionDao,
                habitEntryDao = habitEntryDao,
                remoteSource = remoteSource,
                connectivityObserver = connectivityObserver,
                secureStorage = secureStorage,
                dispatchers = dispatchers
            )
        }

        // ─── createTodo() ─────────────────────────────────────────────────────────

        describe("createTodo()") {
            it("inserts todo in Room with PENDING sync status") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val todo = fakeTodoItem()
                    repository.createTodo(todo)

                    coVerify(exactly = 1) {
                        todoItemDao.insert(match { it.syncStatus == "pending" })
                    }
                }
            }

            it("returns ApiResult.Success with the todo") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val todo = fakeTodoItem(id = "todo-new")
                    val result = repository.createTodo(todo)

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.id shouldBe "todo-new"
                }
            }

            it("calls remote sync when connected") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.updateTodo(any(), any()) } returns
                        ApiResult.Success(fakeTodoItemDto())

                    repository.createTodo(fakeTodoItem())

                    coVerify(atLeast = 1) { remoteSource.updateTodo(any(), any()) }
                }
            }

            it("does NOT call remote when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    repository.createTodo(fakeTodoItem())

                    coVerify(exactly = 0) { remoteSource.updateTodo(any(), any()) }
                }
            }
        }

        // ─── updateTodo() ─────────────────────────────────────────────────────────

        describe("updateTodo()") {
            it("updates todo in Room and returns Success") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val todo = fakeTodoItem(title = "Updated task")
                    val result = repository.updateTodo(todo)

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    coVerify(exactly = 1) { todoItemDao.update(match { it.title == "Updated task" }) }
                }
            }
        }

        // ─── deleteTodo() ─────────────────────────────────────────────────────────

        describe("deleteTodo()") {
            it("deletes from Room and returns Success(Unit)") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.deleteTodo("todo-1")

                    result shouldBe ApiResult.Success(Unit)
                    coVerify(exactly = 1) { todoItemDao.delete(match { it.id == "todo-1" }) }
                }
            }

            it("calls remote delete when connected") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.deleteTodo("todo-1") } returns ApiResult.Success(Unit)

                    repository.deleteTodo("todo-1")

                    coVerify(exactly = 1) { remoteSource.deleteTodo("todo-1") }
                }
            }
        }

        // ─── generateTodosFromPrompt() ────────────────────────────────────────────

        describe("generateTodosFromPrompt()") {
            it("returns NetworkUnavailable when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.generateTodosFromPrompt("Plan a product launch")

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }

            it("returns Success with mapped todos when online") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.generateTodos("Plan a product launch") } returns
                        ApiResult.Success(listOf(fakeTodoItemDto("t1"), fakeTodoItemDto("t2")))

                    val result = repository.generateTodosFromPrompt("Plan a product launch")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.size shouldBe 2
                }
            }
        }

        // ─── createCalendarEvent() ────────────────────────────────────────────────

        describe("createCalendarEvent()") {
            it("inserts event in Room and returns Success") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.createCalendarEvent(fakeCalendarEvent())

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    coVerify(exactly = 1) { calendarEventDao.insert(any()) }
                }
            }

            it("syncs to remote when connected") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.createCalendarEvent(any()) } returns
                        ApiResult.Success(fakeCalendarEventDto())

                    repository.createCalendarEvent(fakeCalendarEvent())

                    coVerify(atLeast = 1) { remoteSource.createCalendarEvent(any()) }
                }
            }
        }

        // ─── deleteCalendarEvent() ────────────────────────────────────────────────

        describe("deleteCalendarEvent()") {
            it("deletes from Room and returns Success(Unit)") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.deleteCalendarEvent("event-1")

                    result shouldBe ApiResult.Success(Unit)
                    coVerify(exactly = 1) { calendarEventDao.delete(match { it.id == "event-1" }) }
                }
            }
        }

        // ─── createReminder() ─────────────────────────────────────────────────────

        describe("createReminder()") {
            it("inserts reminder in Room with PENDING status and returns Success") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.createReminder(fakeReminder())

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    coVerify(exactly = 1) { reminderDao.insert(match { it.syncStatus == "pending" }) }
                }
            }

            it("syncs to remote when connected") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.createReminder(any()) } returns ApiResult.Success(fakeReminderDto())
                    coEvery { reminderDao.update(any()) } returns Unit

                    repository.createReminder(fakeReminder())

                    coVerify(atLeast = 1) { remoteSource.createReminder(any()) }
                }
            }
        }

        // ─── updateReminder() ─────────────────────────────────────────────────────

        describe("updateReminder()") {
            it("updates reminder in Room and returns Success") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.updateReminder(fakeReminder())

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    coVerify(exactly = 1) { reminderDao.update(any()) }
                }
            }
        }

        // ─── deleteReminder() ─────────────────────────────────────────────────────

        describe("deleteReminder()") {
            it("deletes from Room and returns Success(Unit)") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.deleteReminder("rem-1")

                    result shouldBe ApiResult.Success(Unit)
                    coVerify(exactly = 1) { reminderDao.delete(match { it.id == "rem-1" }) }
                }
            }
        }

        // ─── suggestReminder() ────────────────────────────────────────────────────

        describe("suggestReminder()") {
            it("returns NetworkUnavailable when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.suggestReminder("Remind me to review PR before standup")

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }

            it("returns Success with suggested Reminder when online") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.suggestReminder(any()) } returns ApiResult.Success(fakeReminderDto())

                    val result = repository.suggestReminder("some prompt")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                }
            }

            it("propagates error from remote") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.suggestReminder(any()) } returns
                        ApiResult.Error(DomainError.ServerError("AI error", 500))

                    val result = repository.suggestReminder("some prompt")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                }
            }
        }

        // ─── createHabit() ────────────────────────────────────────────────────────

        describe("createHabit()") {
            it("inserts habit in Room and returns Success") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.createHabit(fakeHabit())

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    coVerify(exactly = 1) { habitDefinitionDao.insert(any()) }
                }
            }

            it("syncs to remote when connected") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.createHabit(any()) } returns ApiResult.Success(fakeHabitDefinitionDto())

                    repository.createHabit(fakeHabit())

                    coVerify(atLeast = 1) { remoteSource.createHabit(any()) }
                }
            }
        }

        // ─── deleteHabit() ────────────────────────────────────────────────────────

        describe("deleteHabit()") {
            it("deletes from Room and returns Success(Unit)") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.deleteHabit("habit-1")

                    result shouldBe ApiResult.Success(Unit)
                    coVerify(exactly = 1) { habitDefinitionDao.delete(match { it.id == "habit-1" }) }
                }
            }

            it("calls remote delete when connected") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.deleteHabit("habit-1") } returns ApiResult.Success(Unit)

                    repository.deleteHabit("habit-1")

                    coVerify(exactly = 1) { remoteSource.deleteHabit("habit-1") }
                }
            }
        }

        // ─── logHabitEntry() ──────────────────────────────────────────────────────

        describe("logHabitEntry()") {
            it("inserts entry in Room and returns Success") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val entry = fakeHabitEntry()
                    val result = repository.logHabitEntry(entry)

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.id shouldBe "entry-1"
                    coVerify(exactly = 1) { habitEntryDao.insert(any()) }
                }
            }

            it("calls remote logHabitEntry when connected") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.logHabitEntry(any(), any(), any()) } returns ApiResult.Success(
                        com.aiassistant.data.remote.productivity.HabitEntryDto(
                            id = "entry-remote-1",
                            habitId = "habit-1",
                            userId = "user-1",
                            completedAt = 1_000_000L,
                            note = null
                        )
                    )

                    repository.logHabitEntry(fakeHabitEntry())

                    coVerify(exactly = 1) { remoteSource.logHabitEntry(any(), any(), any()) }
                }
            }
        }

        // ─── getHabitInsights() ───────────────────────────────────────────────────

        describe("getHabitInsights()") {
            it("returns NetworkUnavailable when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.getHabitInsights("habit-1")

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }

            it("returns Success with AI insights when online") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.getHabitInsights("habit-1") } returns
                        ApiResult.Success("You complete this habit best on Mondays.")

                    val result = repository.getHabitInsights("habit-1")

                    result shouldBe ApiResult.Success("You complete this habit best on Mondays.")
                }
            }
        }
    })

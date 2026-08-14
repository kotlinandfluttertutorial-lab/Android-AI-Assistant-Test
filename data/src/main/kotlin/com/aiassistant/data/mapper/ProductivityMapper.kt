/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ProductivityMapper.kt
 * Purpose    : ProductivityMapper — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Domain / Entity Mapper
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
 * Module     : data
 * File       : ProductivityMapper.kt
 * Purpose    : ProductivityMapper — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Domain / Entity Mapper
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
 * ProductivityMapper.kt â€” data module
 *
 * Purpose: Bidirectional mapping between Productivity Suite Room entities and domain models,
 *          and between Retrofit DTOs and Room entities / domain models.
 *
 * Architecture: data module â€” mapper layer. Pure functions, no side effects.
 * Dependencies: core-database entities, domain models, data.remote.productivity DTOs
 *
 * Requirements: 13.1, 16.3, 16.4
 */
package com.aiassistant.data.mapper

import com.aiassistant.core.database.entity.CalendarEventEntity
import com.aiassistant.core.database.entity.HabitDefinitionEntity
import com.aiassistant.core.database.entity.HabitEntryEntity
import com.aiassistant.core.database.entity.ReminderEntity
import com.aiassistant.core.database.entity.TodoItemEntity
import com.aiassistant.data.remote.productivity.CalendarEventDto
import com.aiassistant.data.remote.productivity.HabitDefinitionDto
import com.aiassistant.data.remote.productivity.HabitEntryDto
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

// â”€â”€â”€ TodoItem â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

fun TodoItemEntity.toDomain(): TodoItem = TodoItem(
    id = id,
    userId = userId,
    title = title,
    description = description,
    isCompleted = isCompleted,
    dueDate = dueDate,
    priority = Priority.fromValue(priority),
    tags = decodeTags(tags),
    syncStatus = SyncStatus.fromValue(syncStatus),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun TodoItem.toEntity(syncStatus: String = this.syncStatus.value): TodoItemEntity = TodoItemEntity(
    id = id,
    userId = userId,
    title = title,
    description = description,
    isCompleted = isCompleted,
    dueDate = dueDate,
    priority = priority.value,
    tags = encodeTags(tags),
    syncStatus = syncStatus,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun TodoItemDto.toDomain(): TodoItem = TodoItem(
    id = id,
    userId = userId,
    title = title,
    description = description,
    isCompleted = isCompleted,
    dueDate = dueDate,
    priority = Priority.fromValue(priority),
    tags = tags,
    syncStatus = SyncStatus.fromValue(syncStatus),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun TodoItemDto.toEntity(): TodoItemEntity = TodoItemEntity(
    id = id,
    userId = userId,
    title = title,
    description = description,
    isCompleted = isCompleted,
    dueDate = dueDate,
    priority = priority,
    tags = encodeTags(tags),
    syncStatus = syncStatus,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// â”€â”€â”€ CalendarEvent â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

fun CalendarEventEntity.toDomain(): CalendarEvent = CalendarEvent(
    id = id,
    userId = userId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    location = location,
    isAllDay = isAllDay,
    source = CalendarEventSource.fromValue(source),
    syncStatus = SyncStatus.fromValue(syncStatus),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun CalendarEvent.toEntity(syncStatus: String = this.syncStatus.value): CalendarEventEntity = CalendarEventEntity(
    id = id,
    userId = userId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    location = location,
    isAllDay = isAllDay,
    source = source.value,
    syncStatus = syncStatus,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun CalendarEventDto.toDomain(): CalendarEvent = CalendarEvent(
    id = id,
    userId = userId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    location = location,
    isAllDay = isAllDay,
    source = CalendarEventSource.fromValue(source),
    syncStatus = SyncStatus.fromValue(syncStatus),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun CalendarEventDto.toEntity(): CalendarEventEntity = CalendarEventEntity(
    id = id,
    userId = userId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    location = location,
    isAllDay = isAllDay,
    source = source,
    syncStatus = syncStatus,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// â”€â”€â”€ Reminder â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

fun ReminderEntity.toDomain(): Reminder = Reminder(
    id = id,
    userId = userId,
    title = title,
    triggerTime = triggerTime,
    recurrenceRule = recurrenceRule,
    linkedTodoId = linkedTodoId,
    isCompleted = isCompleted,
    syncStatus = SyncStatus.fromValue(syncStatus),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Reminder.toEntity(syncStatus: String = this.syncStatus.value): ReminderEntity = ReminderEntity(
    id = id,
    userId = userId,
    title = title,
    triggerTime = triggerTime,
    recurrenceRule = recurrenceRule,
    linkedTodoId = linkedTodoId,
    isCompleted = isCompleted,
    syncStatus = syncStatus,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ReminderDto.toDomain(): Reminder = Reminder(
    id = id,
    userId = userId,
    title = title,
    triggerTime = triggerTime,
    recurrenceRule = recurrenceRule,
    linkedTodoId = linkedTodoId,
    isCompleted = isCompleted,
    syncStatus = SyncStatus.fromValue(syncStatus),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ReminderDto.toEntity(): ReminderEntity = ReminderEntity(
    id = id,
    userId = userId,
    title = title,
    triggerTime = triggerTime,
    recurrenceRule = recurrenceRule,
    linkedTodoId = linkedTodoId,
    isCompleted = isCompleted,
    syncStatus = syncStatus,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// â”€â”€â”€ HabitDefinition â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

fun HabitDefinitionEntity.toDomain(): HabitDefinition = HabitDefinition(
    id = id,
    userId = userId,
    name = name,
    description = description,
    recurrence = HabitRecurrence.fromValue(recurrence),
    targetFrequency = targetFrequency,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun HabitDefinition.toEntity(): HabitDefinitionEntity = HabitDefinitionEntity(
    id = id,
    userId = userId,
    name = name,
    description = description,
    recurrence = recurrence.value,
    targetFrequency = targetFrequency,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun HabitDefinitionDto.toDomain(): HabitDefinition = HabitDefinition(
    id = id,
    userId = userId,
    name = name,
    description = description,
    recurrence = HabitRecurrence.fromValue(recurrence),
    targetFrequency = targetFrequency,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun HabitDefinitionDto.toEntity(): HabitDefinitionEntity = HabitDefinitionEntity(
    id = id,
    userId = userId,
    name = name,
    description = description,
    recurrence = recurrence,
    targetFrequency = targetFrequency,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// â”€â”€â”€ HabitEntry â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

fun HabitEntryEntity.toDomain(): HabitEntry = HabitEntry(
    id = id,
    habitId = habitId,
    userId = userId,
    completedAt = completedAt,
    note = note
)

fun HabitEntry.toEntity(): HabitEntryEntity = HabitEntryEntity(
    id = id,
    habitId = habitId,
    userId = userId,
    completedAt = completedAt,
    note = note
)

fun HabitEntryDto.toDomain(): HabitEntry = HabitEntry(
    id = id,
    habitId = habitId,
    userId = userId,
    completedAt = completedAt,
    note = note
)

fun HabitEntryDto.toEntity(): HabitEntryEntity = HabitEntryEntity(
    id = id,
    habitId = habitId,
    userId = userId,
    completedAt = completedAt,
    note = note
)

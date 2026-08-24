/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : AppDatabase.kt
 * Purpose    : AppDatabase — core-database module component
 *
 * Architecture Layer : Core-Database
 * Pattern Used       : Kotlin Class
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
 * Module     : core-database
 * File       : AppDatabase.kt
 * Purpose    : AppDatabase — core-database module component
 *
 * Architecture Layer : Core-Database
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
package com.aiassistant.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aiassistant.core.database.converter.DatabaseConverters
import com.aiassistant.core.database.dao.CalendarEventDao
import com.aiassistant.core.database.dao.ConversationDao
import com.aiassistant.core.database.dao.DocumentDao
import com.aiassistant.core.database.dao.HabitDefinitionDao
import com.aiassistant.core.database.dao.HabitEntryDao
import com.aiassistant.core.database.dao.MemoryDao
import com.aiassistant.core.database.dao.MessageDao
import com.aiassistant.core.database.dao.NoteDao
import com.aiassistant.core.database.dao.ReminderDao
import com.aiassistant.core.database.dao.TodoItemDao
import com.aiassistant.core.database.dao.UserDao
import com.aiassistant.core.database.entity.CalendarEventEntity
import com.aiassistant.core.database.entity.ConversationEntity
import com.aiassistant.core.database.entity.ConversationFtsEntity
import com.aiassistant.core.database.entity.DocumentEntity
import com.aiassistant.core.database.entity.HabitDefinitionEntity
import com.aiassistant.core.database.entity.HabitEntryEntity
import com.aiassistant.core.database.entity.MemoryEntity
import com.aiassistant.core.database.entity.MessageEntity
import com.aiassistant.core.database.entity.MessageFtsEntity
import com.aiassistant.core.database.entity.NoteEntity
import com.aiassistant.core.database.entity.ReminderEntity
import com.aiassistant.core.database.entity.TodoItemEntity
import com.aiassistant.core.database.entity.UserEntity

@Database(
    entities = [
        // Core entities
        UserEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        DocumentEntity::class,
        MemoryEntity::class,
        // Productivity entities
        NoteEntity::class,
        TodoItemEntity::class,
        CalendarEventEntity::class,
        ReminderEntity::class,
        HabitDefinitionEntity::class,
        HabitEntryEntity::class,
        // FTS4 virtual tables
        ConversationFtsEntity::class,
        MessageFtsEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun documentDao(): DocumentDao
    abstract fun memoryDao(): MemoryDao
    abstract fun noteDao(): NoteDao
    abstract fun todoItemDao(): TodoItemDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun reminderDao(): ReminderDao
    abstract fun habitDefinitionDao(): HabitDefinitionDao
    abstract fun habitEntryDao(): HabitEntryDao
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : AppDatabase.kt
 * Purpose    : Single Room database instance for the entire application.
 *              Registers all entities, exposes all DAOs, and applies the
 *              TypeConverters needed to persist complex column types.
 *
 * Architecture Layer : Core-Database — the persistence root.
 *                      Provided as a @Singleton via DatabaseModule; every
 *                      DAO is obtained from this one instance.
 *
 * Dependencies       : Room, all Entity classes, all DAO interfaces,
 *                      DatabaseConverters
 *
 * Design Decision    : version is bumped to 3 to accommodate the three new
 *                      On-Device RAG tables added in Task 44
 *                      (on_device_documents, on_device_chunks,
 *                      query_routing_log).  MIGRATION_2_3 creates these
 *                      tables from scratch — no existing data is affected.
 *                      exportSchema = true keeps a JSON snapshot of each
 *                      schema version in the repo so migrations can be
 *                      validated offline.
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
import com.aiassistant.core.database.dao.OnDeviceChunkDao
import com.aiassistant.core.database.dao.OnDeviceDocumentDao
import com.aiassistant.core.database.dao.QueryRoutingLogDao
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
import com.aiassistant.core.database.entity.OnDeviceChunkEntity
import com.aiassistant.core.database.entity.OnDeviceDocumentEntity
import com.aiassistant.core.database.entity.QueryRoutingLogEntity
import com.aiassistant.core.database.entity.ReminderEntity
import com.aiassistant.core.database.entity.TodoItemEntity
import com.aiassistant.core.database.entity.UserEntity

@Database(
    entities = [
        // ── Core conversation entities ───────────────────────────────────────
        UserEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        DocumentEntity::class,
        MemoryEntity::class,
        // ── Productivity entities ────────────────────────────────────────────
        NoteEntity::class,
        TodoItemEntity::class,
        CalendarEventEntity::class,
        ReminderEntity::class,
        HabitDefinitionEntity::class,
        HabitEntryEntity::class,
        // ── FTS4 virtual tables ──────────────────────────────────────────────
        ConversationFtsEntity::class,
        MessageFtsEntity::class,
        // ── On-Device RAG entities (added v3) ───────────────────────────────
        OnDeviceDocumentEntity::class,
        OnDeviceChunkEntity::class,
        QueryRoutingLogEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    // ── Core DAOs ────────────────────────────────────────────────────────────
    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun documentDao(): DocumentDao
    abstract fun memoryDao(): MemoryDao

    // ── Productivity DAOs ────────────────────────────────────────────────────
    abstract fun noteDao(): NoteDao
    abstract fun todoItemDao(): TodoItemDao
    abstract fun calendarEventDao(): CalendarEventDao
    abstract fun reminderDao(): ReminderDao
    abstract fun habitDefinitionDao(): HabitDefinitionDao
    abstract fun habitEntryDao(): HabitEntryDao

    // ── On-Device RAG DAOs (added v3) ────────────────────────────────────────
    abstract fun onDeviceDocumentDao(): OnDeviceDocumentDao
    abstract fun onDeviceChunkDao(): OnDeviceChunkDao
    abstract fun queryRoutingLogDao(): QueryRoutingLogDao
}

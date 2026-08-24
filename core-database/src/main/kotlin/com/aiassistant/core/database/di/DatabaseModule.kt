/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : DatabaseModule.kt
 * Purpose    : Hilt module providing Database dependencies to the DI graph
 *
 * Architecture Layer : Core-Database
 * Pattern Used       : Hilt DI Module
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
 * File       : DatabaseModule.kt
 * Purpose    : Hilt module providing Database dependencies to the DI graph
 *
 * Architecture Layer : Core-Database
 * Pattern Used       : Hilt DI Module
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
package com.aiassistant.core.database.di

import android.content.Context
import androidx.room.Room
import com.aiassistant.core.database.AppDatabase
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
import com.aiassistant.core.database.migrations.DatabaseMigrations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "ai_assistant_database"
    )
        .addMigrations(DatabaseMigrations.MIGRATION_1_2)
        .build()

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()

    @Provides
    fun provideConversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideDocumentDao(db: AppDatabase): DocumentDao = db.documentDao()

    @Provides
    fun provideMemoryDao(db: AppDatabase): MemoryDao = db.memoryDao()

    @Provides
    fun provideNoteDao(db: AppDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideTodoItemDao(db: AppDatabase): TodoItemDao = db.todoItemDao()

    @Provides
    fun provideCalendarEventDao(db: AppDatabase): CalendarEventDao = db.calendarEventDao()

    @Provides
    fun provideReminderDao(db: AppDatabase): ReminderDao = db.reminderDao()

    @Provides
    fun provideHabitDefinitionDao(db: AppDatabase): HabitDefinitionDao = db.habitDefinitionDao()

    @Provides
    fun provideHabitEntryDao(db: AppDatabase): HabitEntryDao = db.habitEntryDao()
}

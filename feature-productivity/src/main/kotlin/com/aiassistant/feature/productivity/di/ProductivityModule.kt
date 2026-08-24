/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ProductivityModule.kt
 * Purpose    : Hilt module providing Productivity dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-productivity)
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
 * Module     : feature-productivity
 * File       : ProductivityModule.kt
 * Purpose    : Hilt module providing Productivity dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-productivity)
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
/**
 * ProductivityModule.kt â€” feature-productivity module
 *
 * Purpose: Hilt [dagger.Module] providing domain use case instances needed by
 *          [ProductivityViewModel], [CalendarViewModel], and [HabitViewModel].
 *          The use cases do not carry @Inject constructor annotations in the domain
 *          module (pure-Kotlin, no DI framework dependency by design), so they are
 *          provided here via @Provides factory methods.
 *
 * Architecture: feature-productivity â€” installs into [dagger.hilt.android.components.ViewModelComponent].
 * Dependencies: domain (GetTodosUseCase, CreateTodoUseCase, UpdateTodoUseCase,
 *               DeleteTodoUseCase, GenerateTodosFromPromptUseCase,
 *               GetCalendarEventsUseCase, CreateCalendarEventUseCase,
 *               DeleteCalendarEventUseCase,
 *               GetRemindersUseCase, CreateReminderUseCase, UpdateReminderUseCase,
 *               DeleteReminderUseCase, SuggestReminderUseCase,
 *               CreateHabitUseCase, DeleteHabitUseCase, LogHabitEntryUseCase,
 *               GetHabitInsightsUseCase,
 *               ProductivityRepository)
 *
 * Design decisions:
 * - @Provides rather than @Binds because use cases are instantiated by factory.
 * - Scoped to ViewModelComponent so each ViewModel instance gets its own use-case
 *   instances, while the ProductivityRepository (bound at SingletonComponent in the
 *   data module) is shared.
 *
 * Requirements: 8.2, 13.1, 16.3, 16.4, 19.1
 */
package com.aiassistant.feature.productivity.di

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
import com.aiassistant.domain.usecase.productivity.SuggestMeetingTimesUseCase
import com.aiassistant.domain.usecase.productivity.SuggestReminderUseCase
import com.aiassistant.domain.usecase.productivity.UpdateReminderUseCase
import com.aiassistant.domain.usecase.productivity.UpdateTodoUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Hilt module providing use-case instances for the productivity ViewModels.
 */
@Module
@InstallIn(ViewModelComponent::class)
object ProductivityModule {

    // â”€â”€ To-Do use cases â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Provides [GetTodosUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideGetTodosUseCase(repo: ProductivityRepository): GetTodosUseCase = GetTodosUseCase(repo)

    /**
     * Provides [CreateTodoUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideCreateTodoUseCase(repo: ProductivityRepository): CreateTodoUseCase = CreateTodoUseCase(repo)

    /**
     * Provides [UpdateTodoUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideUpdateTodoUseCase(repo: ProductivityRepository): UpdateTodoUseCase = UpdateTodoUseCase(repo)

    /**
     * Provides [DeleteTodoUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideDeleteTodoUseCase(repo: ProductivityRepository): DeleteTodoUseCase = DeleteTodoUseCase(repo)

    /**
     * Provides [GenerateTodosFromPromptUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideGenerateTodosFromPromptUseCase(repo: ProductivityRepository): GenerateTodosFromPromptUseCase =
        GenerateTodosFromPromptUseCase(repo)

    // â”€â”€ Calendar use cases â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Provides [GetCalendarEventsUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideGetCalendarEventsUseCase(repo: ProductivityRepository): GetCalendarEventsUseCase =
        GetCalendarEventsUseCase(repo)

    /**
     * Provides [CreateCalendarEventUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideCreateCalendarEventUseCase(repo: ProductivityRepository): CreateCalendarEventUseCase =
        CreateCalendarEventUseCase(repo)

    /**
     * Provides [DeleteCalendarEventUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideDeleteCalendarEventUseCase(repo: ProductivityRepository): DeleteCalendarEventUseCase =
        DeleteCalendarEventUseCase(repo)

    /**
     * Provides [SuggestMeetingTimesUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideSuggestMeetingTimesUseCase(repo: ProductivityRepository): SuggestMeetingTimesUseCase =
        SuggestMeetingTimesUseCase(repo)

    // â”€â”€ Reminder use cases â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Provides [GetRemindersUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideGetRemindersUseCase(repo: ProductivityRepository): GetRemindersUseCase = GetRemindersUseCase(repo)

    /**
     * Provides [CreateReminderUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideCreateReminderUseCase(repo: ProductivityRepository): CreateReminderUseCase = CreateReminderUseCase(repo)

    /**
     * Provides [UpdateReminderUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideUpdateReminderUseCase(repo: ProductivityRepository): UpdateReminderUseCase = UpdateReminderUseCase(repo)

    /**
     * Provides [DeleteReminderUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideDeleteReminderUseCase(repo: ProductivityRepository): DeleteReminderUseCase = DeleteReminderUseCase(repo)

    /**
     * Provides [SuggestReminderUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideSuggestReminderUseCase(repo: ProductivityRepository): SuggestReminderUseCase =
        SuggestReminderUseCase(repo)

    // â”€â”€ Habit use cases â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Provides [CreateHabitUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideCreateHabitUseCase(repo: ProductivityRepository): CreateHabitUseCase = CreateHabitUseCase(repo)

    /**
     * Provides [DeleteHabitUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideDeleteHabitUseCase(repo: ProductivityRepository): DeleteHabitUseCase = DeleteHabitUseCase(repo)

    /**
     * Provides [LogHabitEntryUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideLogHabitEntryUseCase(repo: ProductivityRepository): LogHabitEntryUseCase = LogHabitEntryUseCase(repo)

    /**
     * Provides [GetHabitInsightsUseCase] backed by the injected [ProductivityRepository].
     */
    @Provides
    fun provideGetHabitInsightsUseCase(repo: ProductivityRepository): GetHabitInsightsUseCase =
        GetHabitInsightsUseCase(repo)
}

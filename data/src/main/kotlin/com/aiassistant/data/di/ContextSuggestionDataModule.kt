/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ContextSuggestionDataModule.kt
 * Purpose    : Hilt module providing ContextSuggestion dependencies to the DI graph
 *
 * Architecture Layer : Data
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
 * ContextSuggestionDataModule.kt — data module
 *
 * Purpose: Hilt [dagger.Module] that wires context suggestion bindings in the data module.
 *
 *          Binds:
 *            - [ContextSuggestionRepositoryImpl] → [ContextSuggestionRepository]
 *
 *          Also provides:
 *            - [GetContextSuggestionsUseCase] (requires [ContextSuggestionRepository]
 *              and [DispatcherProvider])
 *            - [DismissSuggestionUseCase] (pure in-memory, no repository dependency)
 *
 * Architecture: data module — installs into [SingletonComponent] for process-wide singletons.
 *
 * Requirements: 33.1, 33.2, 33.3, 33.5, 33.6, 33.7, 33.8
 */
package com.aiassistant.data.di

import com.aiassistant.data.repository.ContextSuggestionRepositoryImpl
import com.aiassistant.domain.repository.ContextSuggestionRepository
import com.aiassistant.domain.usecase.suggestions.DismissSuggestionUseCase
import com.aiassistant.domain.usecase.suggestions.GetContextSuggestionsUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ContextSuggestionDataModule {

    /**
     * Binds [ContextSuggestionRepositoryImpl] to the [ContextSuggestionRepository] domain interface.
     *
     * Any injection site requesting [ContextSuggestionRepository] receives the singleton
     * [ContextSuggestionRepositoryImpl] constructed by Hilt.
     */
    @Binds
    @Singleton
    abstract fun bindContextSuggestionRepository(impl: ContextSuggestionRepositoryImpl): ContextSuggestionRepository

    companion object {

        /**
         * Provides the [GetContextSuggestionsUseCase] singleton.
         *
         * Scoped as a singleton so the rate-gate [ConcurrentHashMap] inside the use case
         * is shared across all injection sites and correctly gates per screen instance.
         *
         * @param repository         The [ContextSuggestionRepository] singleton.
         * @return A singleton [GetContextSuggestionsUseCase].
         */
        @Provides
        @Singleton
        fun provideGetContextSuggestionsUseCase(repository: ContextSuggestionRepository): GetContextSuggestionsUseCase =
            GetContextSuggestionsUseCase(
                repository = repository
            )

        /**
         * Provides the [DismissSuggestionUseCase] singleton.
         *
         * Scoped as a singleton so the session-scoped dismissal map is shared across
         * all injection sites (Notes, Calendar, Chat ViewModels) and the correct
         * dismissal state is preserved within a session.
         *
         * @return A singleton [DismissSuggestionUseCase].
         */
        @Provides
        @Singleton
        fun provideDismissSuggestionUseCase(): DismissSuggestionUseCase = DismissSuggestionUseCase()
    }
}

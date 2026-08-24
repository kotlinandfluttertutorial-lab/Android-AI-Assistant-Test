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
 *          Provides:
 *            - [SuggestionApiService] Retrofit implementation
 *          Binds:
 *            - [ContextSuggestionRepositoryImpl] → [ContextSuggestionRepository]
 *          Also provides:
 *            - [GetContextSuggestionsUseCase] singleton (rate-gate map must be shared)
 *            - [DismissSuggestionUseCase] singleton (dismissal map must be shared)
 *
 * Architecture: data module — installs into [SingletonComponent] for process-wide singletons.
 *
 * Requirements: 33.1, 33.2, 33.3, 33.5, 33.6, 33.7, 33.8
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.suggestion.SuggestionApiService
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
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class ContextSuggestionDataModule {

    /**
     * Binds [ContextSuggestionRepositoryImpl] to the [ContextSuggestionRepository] domain interface.
     */
    @Binds
    @Singleton
    abstract fun bindContextSuggestionRepository(impl: ContextSuggestionRepositoryImpl): ContextSuggestionRepository

    companion object {

        /**
         * Creates the [SuggestionApiService] Retrofit implementation using the
         * application-level [Retrofit] singleton provided by core-network's NetworkModule.
         */
        @Provides
        @Singleton
        fun provideSuggestionApiService(retrofit: Retrofit): SuggestionApiService =
            retrofit.create(SuggestionApiService::class.java)

        /**
         * Provides the [GetContextSuggestionsUseCase] singleton.
         *
         * Scoped as a singleton so the rate-gate [ConcurrentHashMap] inside the use case
         * is shared across all injection sites and correctly gates per screen instance.
         */
        @Provides
        @Singleton
        fun provideGetContextSuggestionsUseCase(repository: ContextSuggestionRepository): GetContextSuggestionsUseCase =
            GetContextSuggestionsUseCase(repository = repository)

        /**
         * Provides the [DismissSuggestionUseCase] singleton.
         *
         * Scoped as a singleton so the session-scoped dismissal map is shared across
         * all injection sites (Notes, Calendar, Chat ViewModels).
         */
        @Provides
        @Singleton
        fun provideDismissSuggestionUseCase(): DismissSuggestionUseCase = DismissSuggestionUseCase()
    }
}

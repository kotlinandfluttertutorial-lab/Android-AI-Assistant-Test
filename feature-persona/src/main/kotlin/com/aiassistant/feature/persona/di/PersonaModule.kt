/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-persona
 * File       : PersonaModule.kt
 * Purpose    : Hilt module providing Persona dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-persona)
 * Pattern Used       : Hilt DI Module
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *   - Use cases are provided via @Provides since domain module has no @Inject constructors
 *
 * Dependencies:
 *   - domain (CreatePersonaUseCase, DeletePersonaUseCase, SelectPersonaUseCase)
 *   - domain repositories (PersonaRepository, PersonaPreferencesRepository)
 * ============================================================
 */

/**
 * PersonaModule.kt — feature-persona module
 *
 * Purpose: Hilt [dagger.Module] providing domain use case instances needed by
 *          [PersonaViewModel]. The use cases do not carry @Inject constructor
 *          annotations in the domain module (pure-Kotlin, no DI framework dependency
 *          by design), so they are provided here via @Provides factory methods.
 *
 * Architecture: feature-persona — installs into [dagger.hilt.android.components.ViewModelComponent].
 * Dependencies: domain (CreatePersonaUseCase, DeletePersonaUseCase, SelectPersonaUseCase,
 *               PersonaRepository, PersonaPreferencesRepository)
 *
 * Design decisions:
 * - @Provides rather than @Binds because use cases are instantiated by factory.
 * - Scoped to ViewModelComponent so each ViewModel instance gets its own use-case
 *   instances, while the repositories (bound at SingletonComponent) are shared.
 *
 * Requirements: 32.1, 32.3, 32.5, 32.6
 */
package com.aiassistant.feature.persona.di

import com.aiassistant.domain.repository.PersonaPreferencesRepository
import com.aiassistant.domain.repository.PersonaRepository
import com.aiassistant.domain.usecase.persona.CreatePersonaUseCase
import com.aiassistant.domain.usecase.persona.DeletePersonaUseCase
import com.aiassistant.domain.usecase.persona.SelectPersonaUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Hilt module providing use-case instances for the persona ViewModel.
 */
@Module
@InstallIn(ViewModelComponent::class)
object PersonaModule {

    /**
     * Provides [CreatePersonaUseCase] backed by the injected [PersonaRepository].
     *
     * Validates name (1–80 chars), system prompt (1–4,000 chars), scope description
     * (0–500 chars), and enforces the 20-persona limit (Requirement 32.3).
     */
    @Provides
    fun provideCreatePersonaUseCase(repo: PersonaRepository): CreatePersonaUseCase = CreatePersonaUseCase(repo)

    /**
     * Provides [DeletePersonaUseCase] backed by the injected [PersonaRepository].
     *
     * Enforces the admin-locked guard before delegating to the repository
     * (Requirement 32.5).
     */
    @Provides
    fun provideDeletePersonaUseCase(repo: PersonaRepository): DeletePersonaUseCase = DeletePersonaUseCase(repo)

    /**
     * Provides [SelectPersonaUseCase] backed by the injected [PersonaRepository] and
     * [PersonaPreferencesRepository].
     *
     * Validates that the persona exists before persisting the selection to DataStore
     * and informing the AI Orchestrator (Requirement 32.2).
     */
    @Provides
    fun provideSelectPersonaUseCase(
        personaRepository: PersonaRepository,
        personaPreferencesRepository: PersonaPreferencesRepository
    ): SelectPersonaUseCase = SelectPersonaUseCase(personaRepository, personaPreferencesRepository)
}

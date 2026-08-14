/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : PersonaPreferencesRepository.kt
 * Purpose    : Abstraction over the persistence layer for the selected persona ID
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Interface abstraction keeps domain pure while DataStore implementation lives in data
 *
 * Dependencies:
 *   - None (pure Kotlin, zero Android/third-party dependencies)
 * ============================================================
 */

/**
 * PersonaPreferencesRepository.kt
 *
 * Purpose: Domain-layer abstraction for persisting and retrieving the selected persona ID.
 *          The concrete implementation in the data module uses Android DataStore.
 *          Having a separate interface (rather than embedding these operations in
 *          PersonaRepository) keeps the "which persona is selected?" concern isolated from
 *          the "how are personas managed?" concern, and ensures SelectPersonaUseCase
 *          remains in the pure domain module without any DataStore dependency.
 *
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 32.2, 32.6
 *
 * Design decisions:
 * - Separate from PersonaRepository so that SelectPersonaUseCase can be injected with
 *   only the preferences abstraction rather than the full persona repository.
 * - saveSelectedPersonaId(null) means "clear the selection" (no persona active).
 * - getSelectedPersonaId() returns null when no persona has been selected or the
 *   selection has been cleared.
 */

package com.aiassistant.domain.repository

/**
 * Contract for persisting and retrieving the currently selected AI persona ID.
 *
 * Implemented in the `data` module using Android DataStore. Injected into
 * [com.aiassistant.domain.usecase.persona.SelectPersonaUseCase] so the domain layer
 * remains free of Android or DataStore dependencies.
 *
 * WHEN a User selects a Persona, THE AI_Orchestrator SHALL inject that Persona's system
 * prompt, tone, and scope into the LLM_Provider system message for all subsequent Messages
 * in the active Conversation (Requirement 32.2).
 */
interface PersonaPreferencesRepository {

    /**
     * Persists the given [personaId] as the currently selected persona.
     *
     * Passing `null` clears the selection so no persona is injected into subsequent
     * AI Orchestrator calls.
     *
     * @param personaId The ID of the persona to activate, or `null` to deselect.
     */
    suspend fun saveSelectedPersonaId(personaId: String?)

    /**
     * Returns the currently selected persona ID, or `null` if no persona is selected.
     *
     * @return The selected persona's ID string, or `null` if the selection is empty.
     */
    suspend fun getSelectedPersonaId(): String?
}

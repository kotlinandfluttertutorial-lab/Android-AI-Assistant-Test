/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : PersonaRepository.kt
 * Purpose    : Domain contract defining data access operations for Persona entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * PersonaRepository.kt
 *
 * Purpose: Domain-layer repository interface for persona operations.
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain model (Persona)
 *
 * Requirements: 32.1, 32.3, 32.4, 32.5, 32.6
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Persona
import kotlinx.coroutines.flow.Flow

/**
 * Contract for persona data access between the domain and data layers.
 *
 * The data module provides a concrete implementation backed by the remote API.
 * The selected persona ID is persisted in DataStore by [SelectPersonaUseCase].
 */
interface PersonaRepository {

    /**
     * Returns a [Flow] of all personas visible to the current user.
     *
     * This includes:
     * - Personas owned by the current user.
     * - Admin-shared personas that permit the current user's role.
     *
     * The list is filtered by RBAC: only personas whose [Persona.allowedRoles]
     * include the current user's role (or an empty allowedRoles list meaning all roles)
     * are included (Requirement 32.6).
     *
     * @return Cold [Flow] emitting [ApiResult.Success] with the visible persona list.
     */
    fun getPersonas(): Flow<ApiResult<List<Persona>>>

    /**
     * Creates a new persona owned by the current user.
     *
     * Validation (name length, prompt length, 20-persona limit) is enforced in
     * [com.aiassistant.domain.usecase.persona.CreatePersonaUseCase] before calling this method.
     *
     * @param persona The persona to create (id may be ignored; backend assigns a stable UUID).
     * @return [ApiResult.Success] with the created [Persona] on success.
     */
    suspend fun createPersona(persona: Persona): ApiResult<Persona>

    /**
     * Updates an existing persona.
     *
     * @param persona The persona with updated fields. The [Persona.id] must identify an existing record.
     * @return [ApiResult.Success] with the updated [Persona] on success.
     */
    suspend fun updatePersona(persona: Persona): ApiResult<Persona>

    /**
     * Deletes a persona by its ID.
     *
     * Rejection of admin-locked personas is enforced in
     * [com.aiassistant.domain.usecase.persona.DeletePersonaUseCase] before calling this method.
     *
     * @param personaId The unique identifier of the persona to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun deletePersona(personaId: String): ApiResult<Unit>

    /**
     * Returns the count of personas owned by the current user.
     *
     * Used by [com.aiassistant.domain.usecase.persona.CreatePersonaUseCase] to enforce the
     * 20-persona limit (Requirement 32.3).
     *
     * @return [ApiResult.Success] with the current persona count.
     */
    suspend fun getPersonaCount(): ApiResult<Int>

    /**
     * Returns the currently selected persona ID from DataStore, or null if none selected.
     *
     * @return [ApiResult.Success] with the selected persona ID string, or null if none is set.
     */
    suspend fun getSelectedPersonaId(): ApiResult<String?>

    /**
     * Persists the selected persona ID to DataStore.
     *
     * @param personaId The ID of the persona to select, or null to clear the selection.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun setSelectedPersonaId(personaId: String?): ApiResult<Unit>
}

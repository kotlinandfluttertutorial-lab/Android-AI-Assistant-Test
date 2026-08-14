/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DeletePersonaUseCase.kt
 * Purpose    : Encapsulates the 'DeletePersona' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * DeletePersonaUseCase.kt
 *
 * Purpose: Deletes a Persona after verifying the user has permission to do so.
 *          Rejects deletion if the persona is admin-locked and the caller is not an admin.
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), domain repository (PersonaRepository),
 *               domain model (Persona)
 *
 * Requirements: 32.5, 32.6
 *
 * Design decisions:
 * - The use case fetches the full Persona object first to check its adminLocked flag.
 * - If adminLocked == true and isAdmin == false, returns a Forbidden error without
 *   calling deletePersona().
 * - The isAdmin parameter must be provided by the caller (typically derived from the
 *   current User's role in the UI/ViewModel layer).
 */

package com.aiassistant.domain.usecase.persona

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Persona
import com.aiassistant.domain.repository.PersonaRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Use case for deleting an AI persona.
 *
 * WHEN an Admin locks a Persona by setting its `admin_locked` flag, THE AI_Assistant SHALL
 * prevent Users from editing or deleting that Persona (Requirement 32.5).
 *
 * WHERE an Admin configures a role-restricted Persona list, THE AI_Assistant SHALL display
 * only the Personas permitted for the User's role plus the User's own Personas (Requirement 32.6).
 *
 * @param personaRepository Repository providing persona retrieval and deletion operations.
 */
class DeletePersonaUseCase @Inject constructor(private val personaRepository: PersonaRepository) {

    /**
     * Deletes the persona with the given [personaId].
     *
     * Fetches the persona first to check [Persona.adminLocked]. If the persona is admin-locked
     * and [isAdmin] is false, returns [ApiResult.Error] with [DomainError.Forbidden] without
     * calling the repository delete method.
     *
     * @param personaId The unique identifier of the persona to delete.
     * @param isAdmin   Whether the current user has the admin role.
     * @return [ApiResult.Success] with [Unit] on successful deletion,
     *         [ApiResult.Error] with [DomainError.Forbidden] if the persona is admin-locked
     *         and the user is not an admin,
     *         [ApiResult.Error] with other [DomainError] subtypes on server/network failures,
     *         [ApiResult.NetworkUnavailable] when the device has no connectivity.
     */
    suspend operator fun invoke(personaId: String, isAdmin: Boolean): ApiResult<Unit> {
        // ── 1. Fetch the persona to check adminLocked flag ─────────────────────
        val personasResult = personaRepository.getPersonas().first()
        if (personasResult is ApiResult.Error) {
            return personasResult
        }
        if (personasResult is ApiResult.NetworkUnavailable) {
            return personasResult
        }

        val personas = (personasResult as ApiResult.Success).data
        val persona = personas.firstOrNull { it.id == personaId }
            ?: return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Persona not found.",
                    fields = mapOf(
                        FIELD_PERSONA_ID to "The specified persona does not exist or you do not have access to it."
                    )
                )
            )

        // ── 2. Enforce admin-locked check ──────────────────────────────────────
        if (persona.adminLocked && !isAdmin) {
            return ApiResult.Error(
                DomainError.Forbidden(
                    message = "This persona is locked by an administrator and cannot be deleted."
                )
            )
        }

        // ── 3. Delegate to repository ──────────────────────────────────────────
        return personaRepository.deletePersona(personaId)
    }

    internal companion object {
        /** Form field name used in [DomainError.ValidationError.fields] for personaId errors. */
        const val FIELD_PERSONA_ID = "personaId"
    }
}

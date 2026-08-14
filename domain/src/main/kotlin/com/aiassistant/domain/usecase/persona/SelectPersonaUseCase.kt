/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SelectPersonaUseCase.kt
 * Purpose    : Encapsulates the 'SelectPersona' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *   - PersonaPreferencesRepository abstraction keeps DataStore out of domain
 *
 * Dependencies:
 *   - PersonaRepository (for persona existence validation)
 *   - PersonaPreferencesRepository (for persisting selected persona ID)
 * ============================================================
 */

/**
 * SelectPersonaUseCase.kt
 *
 * Purpose: Validates the persona exists and then persists the selected persona ID
 *          to DataStore via PersonaPreferencesRepository, which informs the AI
 *          Orchestrator for all subsequent messages.
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), domain repositories
 *               (PersonaRepository, PersonaPreferencesRepository)
 *
 * Requirements: 32.2
 *
 * Design decisions:
 * - A null personaId means "deselect / clear the active persona". This allows users to
 *   revert to no persona without a separate ClearPersonaUseCase.
 * - Validation: before persisting, checks that the persona exists by fetching from
 *   PersonaRepository.
 * - Persistence is handled by PersonaPreferencesRepository.saveSelectedPersonaId(),
 *   which delegates to the DataStore implementation in the data module. The domain
 *   layer remains clean — it does not know about DataStore or Android APIs.
 * - The AI Orchestrator is implicitly informed on the next Message by reading the
 *   persisted persona ID; no direct reference to the AI Orchestrator is held in the
 *   domain layer.
 */

package com.aiassistant.domain.usecase.persona

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.PersonaPreferencesRepository
import com.aiassistant.domain.repository.PersonaRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Use case for selecting (or deselecting) an AI persona.
 *
 * WHEN a User selects a Persona, THE AI_Orchestrator SHALL inject that Persona's system
 * prompt, tone, and scope into the LLM_Provider system message for all subsequent Messages
 * in the active Conversation, replacing any previously active Persona (Requirement 32.2).
 *
 * @param personaRepository Repository providing persona retrieval for validation.
 * @param personaPreferencesRepository Repository providing the persona selection persistence.
 */
class SelectPersonaUseCase @Inject constructor(
    private val personaRepository: PersonaRepository,
    private val personaPreferencesRepository: PersonaPreferencesRepository
) {

    /**
     * Validates that the persona exists (if non-null) and persists the given [personaId]
     * as the currently selected persona.
     *
     * Passing null deselects the active persona (no persona will be injected into
     * subsequent AI Orchestrator calls until a new persona is selected).
     *
     * @param personaId The ID of the persona to activate, or null to deselect.
     * @return [ApiResult.Success] with [Unit] when the selection is persisted successfully,
     *         [ApiResult.Error] with [DomainError.ValidationError] if the persona does not exist.
     */
    suspend operator fun invoke(personaId: String?): ApiResult<Unit> {
        // ── 1. Allow null (deselection) without validation ─────────────────────
        if (personaId == null) {
            personaPreferencesRepository.saveSelectedPersonaId(null)
            return ApiResult.Success(Unit)
        }

        // ── 2. Validate persona exists ─────────────────────────────────────────
        val personasResult = personaRepository.getPersonas().first()
        if (personasResult is ApiResult.Error) {
            return personasResult
        }
        if (personasResult is ApiResult.NetworkUnavailable) {
            return personasResult
        }

        val personas = (personasResult as ApiResult.Success).data
        val personaExists = personas.any { it.id == personaId }

        if (!personaExists) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Persona not found.",
                    fields = mapOf(
                        FIELD_PERSONA_ID to "The specified persona does not exist or you do not have access to it."
                    )
                )
            )
        }

        // ── 3. Persist selection ───────────────────────────────────────────────
        personaPreferencesRepository.saveSelectedPersonaId(personaId)
        return ApiResult.Success(Unit)
    }

    internal companion object {
        /** Form field name used in [DomainError.ValidationError.fields] for personaId errors. */
        const val FIELD_PERSONA_ID = "personaId"
    }
}

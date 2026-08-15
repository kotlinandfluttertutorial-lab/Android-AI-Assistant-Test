/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CreatePersonaUseCase.kt
 * Purpose    : Encapsulates the 'CreatePersona' business operation
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
 * CreatePersonaUseCase.kt
 *
 * Purpose: Creates a new Persona after validating field lengths and enforcing the 20-persona limit.
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), domain repository (PersonaRepository),
 *               domain model (Persona)
 *
 * Requirements: 32.1, 32.3
 *
 * Design decisions:
 * - All validation is performed in the domain layer before any repository call:
 *   1. Name length: 1–80 characters
 *   2. System prompt length: 1–4,000 characters
 *   3. Scope description length: 0–500 characters
 *   4. User's persona count: must be < 20
 * - Returns ApiResult.Error wrapping DomainError.ValidationError (with field-level detail)
 *   on validation failure so the UI can display inline field errors.
 * - The 20-persona limit check is performed by calling PersonaRepository.getPersonaCount()
 *   before calling createPersona().
 */

package com.aiassistant.domain.usecase.persona

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Persona
import com.aiassistant.domain.repository.PersonaRepository
import javax.inject.Inject

/**
 * Use case for creating a new AI persona.
 *
 * THE AI_Assistant SHALL allow each User to create, name, save, and delete custom Persona
 * objects (Requirement 32.1).
 *
 * THE AI_Assistant SHALL allow each User to store up to 20 Personas. IF a User attempts to
 * create a 21st Persona, THE AI_Assistant SHALL return an error (Requirement 32.3).
 *
 * Validates inputs locally before calling [PersonaRepository.createPersona]:
 * 1. Name: 1–80 characters
 * 2. System prompt: 1–4,000 characters
 * 3. Scope description: 0–500 characters
 * 4. User persona count: must be < [MAX_PERSONAS_PER_USER]
 *
 * @param personaRepository Repository providing persona count and creation operations.
 */
class CreatePersonaUseCase @Inject constructor(private val personaRepository: PersonaRepository) {

    /**
     * Executes the persona creation operation.
     *
     * Validates [persona] fields first. If any validation fails, returns
     * [ApiResult.Error] with a [DomainError.ValidationError] containing a field-level
     * map entry describing the problem. The repository is NOT called on invalid input.
     *
     * @param persona The persona to create (id may be ignored; backend assigns a stable UUID).
     * @return [ApiResult.Success] with the created [Persona] when successful,
     *         [ApiResult.Error] with [DomainError.ValidationError] on invalid input or limit exceeded,
     *         [ApiResult.Error] with other [DomainError] subtypes on server/network failures,
     *         [ApiResult.NetworkUnavailable] when the device has no connectivity.
     */
    suspend operator fun invoke(persona: Persona): ApiResult<Persona> {
        // ── 1. Validate name length ────────────────────────────────────────────
        if (persona.name.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Persona name must not be blank.",
                    fields = mapOf(FIELD_NAME to "Name is required.")
                )
            )
        }
        if (persona.name.length > MAX_NAME_LENGTH) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Persona name must be at most $MAX_NAME_LENGTH characters.",
                    fields = mapOf(
                        FIELD_NAME to
                            "Name must be at most $MAX_NAME_LENGTH characters (currently ${persona.name.length})."
                    )
                )
            )
        }

        // ── 2. Validate system prompt length ───────────────────────────────────
        if (persona.systemPrompt.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "System prompt must not be blank.",
                    fields = mapOf(FIELD_SYSTEM_PROMPT to "System prompt is required.")
                )
            )
        }
        if (persona.systemPrompt.length > MAX_SYSTEM_PROMPT_LENGTH) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "System prompt must be at most $MAX_SYSTEM_PROMPT_LENGTH characters.",
                    fields = mapOf(
                        FIELD_SYSTEM_PROMPT to
                            "System prompt must be at most $MAX_SYSTEM_PROMPT_LENGTH characters " +
                            "(currently ${persona.systemPrompt.length})."
                    )
                )
            )
        }

        // ── 3. Validate scope description length ───────────────────────────────
        val scopeDesc = persona.scopeDescription ?: ""
        if (scopeDesc.length > MAX_SCOPE_DESCRIPTION_LENGTH) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Scope description must be at most $MAX_SCOPE_DESCRIPTION_LENGTH characters.",
                    fields = mapOf(
                        FIELD_SCOPE_DESCRIPTION to
                            "Scope description must be at most $MAX_SCOPE_DESCRIPTION_LENGTH characters " +
                            "(currently ${scopeDesc.length})."
                    )
                )
            )
        }

        // ── 4. Check persona count limit ───────────────────────────────────────
        val countResult = personaRepository.getPersonaCount()
        if (countResult is ApiResult.Error) {
            // Propagate repository error (e.g., network failure)
            return countResult
        }
        if (countResult is ApiResult.NetworkUnavailable) {
            return countResult
        }
        val currentCount = (countResult as ApiResult.Success).data
        if (currentCount >= MAX_PERSONAS_PER_USER) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Persona limit reached. You can store up to $MAX_PERSONAS_PER_USER personas.",
                    fields = mapOf(
                        FIELD_GENERAL to
                            "You have reached the maximum of $MAX_PERSONAS_PER_USER personas. " +
                            "Please delete an existing persona before creating a new one."
                    )
                )
            )
        }

        // ── 5. Delegate to repository ──────────────────────────────────────────
        return personaRepository.createPersona(persona)
    }

    internal companion object {
        /** Maximum number of personas a single user can own (Requirement 32.3). */
        const val MAX_PERSONAS_PER_USER = 20

        /** Maximum length of the persona name in characters (Requirement 32.1). */
        const val MAX_NAME_LENGTH = 80

        /** Maximum length of the system prompt in characters (Requirement 32.1). */
        const val MAX_SYSTEM_PROMPT_LENGTH = 4_000

        /** Maximum length of the scope description in characters (Requirement 32.1). */
        const val MAX_SCOPE_DESCRIPTION_LENGTH = 500

        /** Form field name used in [DomainError.ValidationError.fields] for name errors. */
        const val FIELD_NAME = "name"

        /** Form field name used in [DomainError.ValidationError.fields] for system prompt errors. */
        const val FIELD_SYSTEM_PROMPT = "systemPrompt"

        /** Form field name used in [DomainError.ValidationError.fields] for scope description errors. */
        const val FIELD_SCOPE_DESCRIPTION = "scopeDescription"

        /** Form field name used for general/non-field-specific validation errors. */
        const val FIELD_GENERAL = "general"
    }
}

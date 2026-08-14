/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-persona
 * File       : PersonaUiState.kt
 * Purpose    : Sealed class representing every observable UI state for the persona feature
 *
 * Architecture Layer : Feature (feature-persona)
 * Pattern Used       : UI State Sealed Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *   - State-driven UI via StateFlow / Compose
 *
 * Dependencies:
 *   - domain (Persona)
 * ============================================================
 */

/**
 * PersonaUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the persona feature,
 *          covering the list, editor, loading, and error states.
 * Architecture: feature-persona — MVVM presentation layer.
 * Dependencies: domain (Persona)
 *
 * Requirements: 32.1, 32.3, 32.5, 32.6, 32.7
 */
package com.aiassistant.feature.persona

import com.aiassistant.domain.model.Persona

/**
 * Represents every possible UI state in the persona feature.
 *
 * The [PersonaViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Composables observe it and render accordingly.
 */
sealed class PersonaUiState {

    /** A data load or navigation transition is in progress. */
    data object Loading : PersonaUiState()

    /**
     * The persona list screen is active.
     *
     * @param personas        The filtered list of personas visible to the current user
     *                        (own personas + admin-shared personas for the user's RBAC role).
     * @param selectedPersonaId The currently active persona ID, or null if none selected.
     * @param limitError      Non-null when the 20-persona limit has been reached;
     *                        contains the inline error message (Requirement 32.3).
     */
    data class PersonaList(
        val personas: List<Persona>,
        val selectedPersonaId: String?,
        val limitError: String? = null
    ) : PersonaUiState()

    /**
     * The persona editor screen is active.
     *
     * @param persona      The persona being created or edited (draft state).
     * @param isNew        True when creating a new persona; false when editing an existing one.
     * @param isSaving     True while the save operation is in progress.
     * @param fieldErrors  Map of field name → validation error message for inline display.
     *                     Keys match the constants in [com.aiassistant.domain.usecase.persona.CreatePersonaUseCase].
     * @param generalError Non-null when a general (non-field-specific) error occurs,
     *                     such as the 20-persona limit being exceeded (Requirement 32.3).
     */
    data class PersonaEditor(
        val persona: Persona,
        val isNew: Boolean,
        val isSaving: Boolean = false,
        val fieldErrors: Map<String, String> = emptyMap(),
        val generalError: String? = null
    ) : PersonaUiState()

    /**
     * An operation failed.
     *
     * @param message Human-readable error message for the error banner.
     */
    data class Error(val message: String) : PersonaUiState()
}

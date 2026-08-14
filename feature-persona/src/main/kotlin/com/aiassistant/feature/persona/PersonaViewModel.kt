/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-persona
 * File       : PersonaViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Persona feature
 *
 * Architecture Layer : Feature (feature-persona)
 * Pattern Used       : MVVM ViewModel
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *   - SharedFlow for cross-feature persona switch events (Requirement 32.7)
 *   - StateFlow for UI state
 *
 * Dependencies:
 *   - domain (CreatePersonaUseCase, DeletePersonaUseCase, SelectPersonaUseCase, PersonaRepository)
 *   - core-common (DispatcherProvider, ApiResult, DomainError)
 * ============================================================
 */

/**
 * PersonaViewModel.kt
 *
 * Purpose: Manages all UI state and orchestrates use case calls for the persona feature,
 *          including listing, editing, saving, deleting, and selecting personas. Also emits
 *          PersonaSwitchEvent via SharedFlow so feature-chat can insert a system message
 *          in the conversation timeline on persona switch (Requirement 32.7).
 * Architecture: feature-persona — MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (CreatePersonaUseCase, DeletePersonaUseCase, SelectPersonaUseCase,
 *               PersonaRepository), core-common (DispatcherProvider, ApiResult, DomainError)
 *
 * Requirements: 32.1, 32.3, 32.5, 32.6, 32.7
 */
package com.aiassistant.feature.persona

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Persona
import com.aiassistant.domain.model.PersonaTone
import com.aiassistant.domain.repository.PersonaRepository
import com.aiassistant.domain.usecase.persona.CreatePersonaUseCase
import com.aiassistant.domain.usecase.persona.DeletePersonaUseCase
import com.aiassistant.domain.usecase.persona.SelectPersonaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Event emitted when the user selects a persona, so that [feature-chat] can insert a
 * system message in the conversation timeline (Requirement 32.7).
 *
 * @param personaName The display name of the newly selected persona.
 * @param timestamp   Epoch milliseconds of the switch event.
 */
data class PersonaSwitchEvent(val personaName: String, val timestamp: Long)

/**
 * ViewModel for the persona list and persona editor flows.
 *
 * Exposes a [StateFlow] of [PersonaUiState] that composables observe, plus a
 * [SharedFlow] of [PersonaSwitchEvent] for cross-feature integration with feature-chat.
 * All blocking work (network calls, database operations) is dispatched on
 * [DispatcherProvider.io].
 */
@HiltViewModel
class PersonaViewModel @Inject constructor(
    private val createPersonaUseCase: CreatePersonaUseCase,
    private val deletePersonaUseCase: DeletePersonaUseCase,
    private val selectPersonaUseCase: SelectPersonaUseCase,
    private val personaRepository: PersonaRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // ─── State ────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<PersonaUiState>(PersonaUiState.Loading)

    /** Observable persona UI state. */
    val uiState: StateFlow<PersonaUiState> = _uiState.asStateFlow()

    // ─── Persona switch events (Requirement 32.7) ─────────────────────────────

    private val _personaSwitchEvents = MutableSharedFlow<PersonaSwitchEvent>(extraBufferCapacity = 8)

    /**
     * SharedFlow emitting a [PersonaSwitchEvent] each time the user activates a persona.
     *
     * [feature-chat] observes this flow and inserts a system message in the conversation
     * timeline showing the persona name and timestamp (Requirement 32.7).
     */
    val personaSwitchEvents: SharedFlow<PersonaSwitchEvent> = _personaSwitchEvents.asSharedFlow()

    // ─── Init ─────────────────────────────────────────────────────────────────

    init {
        loadPersonas()
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Loads all visible personas for the current user and emits [PersonaUiState.PersonaList].
     *
     * Collects from [PersonaRepository.getPersonas] (already RBAC-filtered by the data layer)
     * and also fetches the selected persona ID to indicate which is active.
     *
     * Requirements: 32.1, 32.6
     */
    fun loadPersonas() {
        viewModelScope.launch {
            _uiState.value = PersonaUiState.Loading
            personaRepository.getPersonas().collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        val selectedIdResult = withContext(dispatchers.io) {
                            personaRepository.getSelectedPersonaId()
                        }
                        val selectedId = (selectedIdResult as? ApiResult.Success)?.data
                        _uiState.value = PersonaUiState.PersonaList(
                            personas = result.data,
                            selectedPersonaId = selectedId
                        )
                    }
                    is ApiResult.Error -> _uiState.value = PersonaUiState.Error(result.error.message)
                    is ApiResult.NetworkUnavailable -> _uiState.value = PersonaUiState.PersonaList(
                        personas = emptyList(),
                        selectedPersonaId = null
                    )
                    is ApiResult.Loading -> _uiState.value = PersonaUiState.Loading
                }
            }
        }
    }

    /**
     * Transitions to [PersonaUiState.PersonaEditor] with a blank persona (isNew = true).
     *
     * Requirement: 32.1
     */
    fun openNewPersona() {
        val now = Instant.now().toEpochMilli()
        val blankPersona = Persona(
            id = UUID.randomUUID().toString(),
            userId = "",
            name = "",
            systemPrompt = "",
            tone = PersonaTone.PROFESSIONAL,
            scopeDescription = null,
            adminLocked = false,
            allowedRoles = emptyList(),
            createdAt = now,
            updatedAt = now
        )
        _uiState.value = PersonaUiState.PersonaEditor(persona = blankPersona, isNew = true)
    }

    /**
     * Transitions to [PersonaUiState.PersonaEditor] with the given [persona] (isNew = false).
     *
     * No-op if [persona.adminLocked] is true — admin-locked personas cannot be edited
     * (Requirement 32.5). The guard is enforced here in the ViewModel in addition to
     * the UI hiding the edit button.
     *
     * @param persona The persona to open for editing.
     */
    fun openEditor(persona: Persona) {
        if (persona.adminLocked) return
        _uiState.value = PersonaUiState.PersonaEditor(persona = persona, isNew = false)
    }

    /**
     * Updates the draft in the current [PersonaUiState.PersonaEditor] state without persisting.
     *
     * No repository call is made — changes are buffered in state until the user saves.
     *
     * @param name              Updated persona name.
     * @param systemPrompt      Updated system prompt text.
     * @param tone              Updated tone selection.
     * @param scopeDescription  Updated scope description (may be blank).
     */
    fun updateDraft(name: String, systemPrompt: String, tone: PersonaTone, scopeDescription: String) {
        val currentState = _uiState.value as? PersonaUiState.PersonaEditor ?: return
        _uiState.value = currentState.copy(
            persona = currentState.persona.copy(
                name = name,
                systemPrompt = systemPrompt,
                tone = tone,
                scopeDescription = scopeDescription.ifBlank { null },
                updatedAt = Instant.now().toEpochMilli()
            )
        )
    }

    /**
     * Persists the persona via use case (create) or repository (update).
     *
     * - New personas go through [CreatePersonaUseCase] which validates fields and the
     *   20-persona limit (Requirement 32.3).
     * - Existing personas are updated via [PersonaRepository.updatePersona].
     *
     * Field-level [DomainError.ValidationError] errors are mapped into
     * [PersonaUiState.PersonaEditor.fieldErrors]. The FIELD_GENERAL error (20-persona
     * limit) is mapped to [PersonaUiState.PersonaEditor.generalError].
     *
     * On success, calls [loadPersonas] to refresh the list.
     *
     * @param persona The persona draft to persist.
     * @param isNew   True when creating a new persona.
     */
    fun savePersona(persona: Persona, isNew: Boolean) {
        val currentState = _uiState.value as? PersonaUiState.PersonaEditor ?: return
        _uiState.value = currentState.copy(isSaving = true, fieldErrors = emptyMap(), generalError = null)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                if (isNew) {
                    createPersonaUseCase(persona)
                } else {
                    personaRepository.updatePersona(persona)
                }
            }
            when (result) {
                is ApiResult.Success -> loadPersonas()
                is ApiResult.Error -> {
                    val error = result.error
                    if (error is DomainError.ValidationError) {
                        val fields = error.fields
                        // FIELD_GENERAL = "general" (mirrors CreatePersonaUseCase internal constant)
                        val generalError = fields["general"]
                        val fieldErrors = fields.filterKeys { it != "general" }
                        _uiState.value = currentState.copy(
                            isSaving = false,
                            fieldErrors = fieldErrors,
                            generalError = generalError
                        )
                    } else {
                        _uiState.value = currentState.copy(
                            isSaving = false,
                            generalError = error.message
                        )
                    }
                }
                is ApiResult.NetworkUnavailable -> _uiState.value = currentState.copy(
                    isSaving = false,
                    generalError = "No network connection. Please try again when you're back online."
                )
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Deletes the persona identified by [personaId] via [DeletePersonaUseCase].
     *
     * [DeletePersonaUseCase] enforces the admin-locked guard server-side.
     * On success, calls [loadPersonas] to refresh the list.
     *
     * @param personaId The ID of the persona to delete.
     * @param isAdmin   Whether the current user has the admin role.
     */
    fun deletePersona(personaId: String, isAdmin: Boolean) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                deletePersonaUseCase(personaId, isAdmin)
            }
            when (result) {
                is ApiResult.Success -> loadPersonas()
                is ApiResult.Error -> _uiState.value = PersonaUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> _uiState.value = PersonaUiState.Error(
                    "No network connection. Unable to delete persona."
                )
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Selects or deselects the active persona via [SelectPersonaUseCase].
     *
     * On success:
     * - Emits a [PersonaSwitchEvent] if personaId is non-null (Requirement 32.7).
     * - Calls [loadPersonas] to refresh [PersonaUiState.PersonaList.selectedPersonaId].
     *
     * @param personaId The ID of the persona to activate, or null to deselect.
     */
    fun selectPersona(personaId: String?) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                selectPersonaUseCase(personaId)
            }
            when (result) {
                is ApiResult.Success -> {
                    // Emit switch event for feature-chat timeline integration (Requirement 32.7)
                    if (personaId != null) {
                        val personaName = resolvePersonaName(personaId)
                        if (personaName != null) {
                            _personaSwitchEvents.emit(
                                PersonaSwitchEvent(
                                    personaName = personaName,
                                    timestamp = Instant.now().toEpochMilli()
                                )
                            )
                        }
                    }
                    loadPersonas()
                }
                is ApiResult.Error -> _uiState.value = PersonaUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> _uiState.value = PersonaUiState.Error(
                    "No network connection. Unable to select persona."
                )
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Navigates back to the persona list by calling [loadPersonas].
     */
    fun backToList() {
        loadPersonas()
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Returns the name of the persona with the given [personaId] from the current list
     * state, or null if not found. Used to populate [PersonaSwitchEvent.personaName].
     */
    private fun resolvePersonaName(personaId: String): String? = (_uiState.value as? PersonaUiState.PersonaList)
        ?.personas
        ?.firstOrNull { it.id == personaId }
        ?.name
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-profile
 * File       : ProfileViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Profile
 *              and Memory Management feature — including data export and account deletion.
 *
 * Architecture Layer : Feature (feature-profile)
 * Pattern Used       : MVVM ViewModel
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *   - StateFlow-driven unidirectional data flow
 *
 * Dependencies:
 *   - domain (UserRepository, MemoryRepository, GetMemoriesUseCase, DeleteMemoryUseCase)
 *   - core-common (DispatcherProvider, ApiResult)
 *   - feature-profile (ProfileUiState)
 *
 * Design decisions:
 * - [UserRepository.getCurrentUser] and [GetMemoriesUseCase] flows are combined so the
 *   UI always receives a single coherent state snapshot with both user and memory data.
 * - [MemoryRepository] is injected directly for updateMemory (no dedicated UpdateMemoryUseCase).
 * - [UserRepository.requestDataExport] maps to [DataExportStatus] state transitions.
 * - [UserRepository.deleteAccount] drives the multi-step [AccountDeletionState] flow; on
 *   success the caller should navigate to auth screen via the [onAccountDeleted] side effect.
 * - Edit state (dialog open/closed, draft content, saving flag) lives inside [Content] so
 *   composables are driven entirely by observable state without local composable memory.
 * - [deletingMemoryIds] tracks concurrent in-flight deletes for per-row loading indicators.
 *
 * Requirements: 7.3, 7.4, 28.1, 28.2
 * ============================================================
 */
package com.aiassistant.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.model.User
import com.aiassistant.domain.repository.MemoryRepository
import com.aiassistant.domain.repository.UserRepository
import com.aiassistant.domain.usecase.memory.DeleteMemoryUseCase
import com.aiassistant.domain.usecase.memory.GetMemoriesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Profile and Memory Management screen.
 *
 * Exposes a [StateFlow] of [ProfileUiState] that composables observe. All blocking work
 * (network calls) is dispatched on [DispatcherProvider.io].
 *
 * Side effects that require navigation (e.g., account deleted) are delivered via the
 * [profileEvents] shared flow so the composable can navigate without coupling to the
 * ViewModel's state.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val memoryRepository: MemoryRepository,
    private val getMemoriesUseCase: GetMemoriesUseCase,
    private val deleteMemoryUseCase: DeleteMemoryUseCase,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // ─── State ────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)

    /** Observable profile UI state. */
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    // ─── One-shot events ──────────────────────────────────────────────────────

    private val _profileEvents = MutableSharedFlow<ProfileEvent>(extraBufferCapacity = 1)

    /**
     * One-shot navigation/lifecycle events emitted by the ViewModel.
     * Currently carries [ProfileEvent.AccountDeleted] to trigger navigation to the auth screen.
     */
    val profileEvents: SharedFlow<ProfileEvent> = _profileEvents.asSharedFlow()

    // ─── Init ─────────────────────────────────────────────────────────────────

    init {
        observeProfile()
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Combines [UserRepository.getCurrentUser] and [GetMemoriesUseCase] flows into a single
     * [ProfileUiState.Content] emission.
     *
     * Either error from either flow transitions to [ProfileUiState.Error]. Both flows must
     * emit a [ApiResult.Success] before [Content] is emitted; until then [Loading] persists.
     */
    private fun observeProfile() {
        viewModelScope.launch {
            combine(
                userRepository.getCurrentUser(),
                getMemoriesUseCase()
            ) { userResult, memoriesResult ->
                val existing = _uiState.value as? ProfileUiState.Content

                when {
                    userResult is ApiResult.Error ->
                        ProfileUiState.Error(userResult.error.message)

                    memoriesResult is ApiResult.Error ->
                        ProfileUiState.Error(memoriesResult.error.message)

                    userResult is ApiResult.NetworkUnavailable ||
                        memoriesResult is ApiResult.NetworkUnavailable ->
                        ProfileUiState.Error("No network connection. Please try again.")

                    userResult is ApiResult.Success && memoriesResult is ApiResult.Success -> {
                        @Suppress("UNCHECKED_CAST")
                        val user = (userResult as ApiResult.Success<User?>).data

                        @Suppress("UNCHECKED_CAST")
                        val memories = (memoriesResult as ApiResult.Success<List<Memory>>).data
                        ProfileUiState.Content(
                            user = user,
                            memories = memories,
                            // Preserve in-flight delete tracking across re-emissions
                            deletingMemoryIds = existing?.deletingMemoryIds ?: emptySet(),
                            // Preserve in-progress action states across re-emissions
                            dataExportStatus = existing?.dataExportStatus ?: DataExportStatus.Idle,
                            accountDeletionState = existing?.accountDeletionState
                                ?: AccountDeletionState.Idle,
                            // Close any open dialog on data refresh to avoid stale state
                            editingMemory = null,
                            editContent = "",
                            isSavingEdit = false,
                            isEditingName = false,
                            editingName = "",
                            isSavingName = false,
                            errorMessage = existing?.errorMessage
                        )
                    }

                    else -> ProfileUiState.Loading
                }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    // ─── Memory actions ───────────────────────────────────────────────────────

    /**
     * Opens the edit dialog for [memory], pre-populating the text field with its current
     * content (Requirement 7.3).
     */
    fun startEditMemory(memory: Memory) {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        _uiState.value = current.copy(
            editingMemory = memory,
            editContent = memory.content
        )
    }

    /**
     * Updates the draft text in the edit dialog without committing to the backend.
     */
    fun updateEditContent(content: String) {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        _uiState.value = current.copy(editContent = content)
    }

    /**
     * Dismisses the edit dialog without saving any changes.
     */
    fun cancelEditMemory() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        _uiState.value = current.copy(
            editingMemory = null,
            editContent = ""
        )
    }

    /**
     * Commits the edited memory content to the backend (Requirement 7.3).
     *
     * Sets [ProfileUiState.Content.isSavingEdit] true while the call is in flight.
     * On success the dialog closes. On failure an [errorMessage] is set.
     */
    fun saveMemoryEdit() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        val memory = current.editingMemory ?: return

        _uiState.value = current.copy(isSavingEdit = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                memoryRepository.updateMemory(memory.id, current.editContent)
            }
            val afterSave = _uiState.value as? ProfileUiState.Content ?: return@launch
            when (result) {
                is ApiResult.Success -> {
                    _uiState.value = afterSave.copy(
                        editingMemory = null,
                        editContent = "",
                        isSavingEdit = false
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = afterSave.copy(
                        isSavingEdit = false,
                        errorMessage = result.error.message
                    )
                }
                else -> {
                    _uiState.value = afterSave.copy(
                        isSavingEdit = false,
                        errorMessage = "Failed to save changes. Please try again."
                    )
                }
            }
        }
    }

    /**
     * Deletes the memory identified by [memoryId] (Requirements 7.3, 7.4).
     *
     * Adds [memoryId] to [ProfileUiState.Content.deletingMemoryIds] immediately for per-row
     * loading indicator feedback. The backend SLA (embedding removed within 10 seconds) is
     * enforced server-side.
     */
    fun deleteMemory(memoryId: String) {
        val current = _uiState.value as? ProfileUiState.Content ?: return

        _uiState.value = current.copy(
            deletingMemoryIds = current.deletingMemoryIds + memoryId
        )

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                deleteMemoryUseCase(memoryId)
            }
            val afterDelete = _uiState.value as? ProfileUiState.Content ?: return@launch
            when (result) {
                is ApiResult.Success -> {
                    _uiState.value = afterDelete.copy(
                        deletingMemoryIds = afterDelete.deletingMemoryIds - memoryId
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = afterDelete.copy(
                        deletingMemoryIds = afterDelete.deletingMemoryIds - memoryId,
                        errorMessage = result.error.message
                    )
                }
                else -> {
                    _uiState.value = afterDelete.copy(
                        deletingMemoryIds = afterDelete.deletingMemoryIds - memoryId,
                        errorMessage = "Failed to delete memory. Please try again."
                    )
                }
            }
        }
    }

    // ─── Profile name editing ─────────────────────────────────────────────────

    /**
     * Opens the edit-name dialog, pre-populating it with the current display name.
     */
    fun startEditName() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        _uiState.value = current.copy(
            isEditingName = true,
            editingName = current.user?.displayName ?: ""
        )
    }

    /**
     * Updates the draft display name in the edit-name dialog.
     */
    fun updateEditingName(name: String) {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        _uiState.value = current.copy(editingName = name)
    }

    /**
     * Dismisses the edit-name dialog without saving.
     */
    fun cancelEditName() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        _uiState.value = current.copy(
            isEditingName = false,
            editingName = ""
        )
    }

    /**
     * Commits the new display name to the backend (Requirement 28.1 — user profile editing).
     *
     * Sets [ProfileUiState.Content.isSavingName] true while the call is in flight.
     * On success the dialog closes and the user profile updates via the combine flow.
     * On failure an [errorMessage] is set.
     */
    fun saveDisplayName() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        val newName = current.editingName.trim()
        if (newName.isBlank()) return

        _uiState.value = current.copy(isSavingName = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                userRepository.updateDisplayName(newName)
            }
            val afterSave = _uiState.value as? ProfileUiState.Content ?: return@launch
            when (result) {
                is ApiResult.Success -> {
                    _uiState.value = afterSave.copy(
                        isEditingName = false,
                        editingName = "",
                        isSavingName = false
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = afterSave.copy(
                        isSavingName = false,
                        errorMessage = result.error.message
                    )
                }
                else -> {
                    _uiState.value = afterSave.copy(
                        isSavingName = false,
                        errorMessage = "Failed to update display name. Please try again."
                    )
                }
            }
        }
    }

    // ─── Data export ──────────────────────────────────────────────────────────

    /**
     * Sends the data export request to the backend (Requirement 28.1).
     *
     * Transitions [DataExportStatus]: Idle → Requesting → Requested / Failed.
     * The backend prepares the data archive asynchronously (up to 24 hours) and notifies
     * the user when ready. The UI shows the status while the request is being acknowledged.
     */
    fun requestDataExport() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        if (current.dataExportStatus is DataExportStatus.Requesting) return // prevent double-submit

        _uiState.value = current.copy(dataExportStatus = DataExportStatus.Requesting)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                userRepository.requestDataExport()
            }
            val afterRequest = _uiState.value as? ProfileUiState.Content ?: return@launch
            val newStatus = when (result) {
                is ApiResult.Success -> DataExportStatus.Requested
                is ApiResult.Error -> DataExportStatus.Failed(result.error.message)
                else -> DataExportStatus.Failed("Export request failed. Please try again.")
            }
            _uiState.value = afterRequest.copy(dataExportStatus = newStatus)
        }
    }

    /**
     * Resets the data export status back to [DataExportStatus.Idle] so the user can
     * dismiss the result banner.
     */
    fun dismissExportStatus() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        _uiState.value = current.copy(dataExportStatus = DataExportStatus.Idle)
    }

    // ─── Account deletion ─────────────────────────────────────────────────────

    /**
     * Opens the account deletion confirmation dialog (Requirement 28.2).
     */
    fun initiateAccountDeletion() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        _uiState.value = current.copy(
            accountDeletionState = AccountDeletionState.Confirming()
        )
    }

    /**
     * Updates the confirmation text field inside the deletion dialog.
     *
     * The user must type "DELETE" exactly to enable the confirm button.
     */
    fun updateDeletionConfirmationInput(input: String) {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        val confirming = current.accountDeletionState as? AccountDeletionState.Confirming ?: return
        _uiState.value = current.copy(
            accountDeletionState = confirming.copy(confirmationInput = input)
        )
    }

    /**
     * Dismisses the account deletion confirmation dialog without taking action.
     */
    fun cancelAccountDeletion() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        _uiState.value = current.copy(accountDeletionState = AccountDeletionState.Idle)
    }

    /**
     * Confirms account deletion and sends the DELETE request to the backend (Requirement 28.2).
     *
     * Only proceeds if the user typed "DELETE" as confirmation. On success emits
     * [ProfileEvent.AccountDeleted] so the caller navigates to the auth screen.
     * On failure sets [AccountDeletionState.Failed] with the error message.
     */
    fun confirmAccountDeletion() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        val confirming = current.accountDeletionState as? AccountDeletionState.Confirming ?: return
        if (confirming.confirmationInput != DELETION_CONFIRMATION_PHRASE) return

        _uiState.value =
            current.copy(accountDeletionState = AccountDeletionState.Deleting(confirming.confirmationInput))

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                userRepository.deleteAccount()
            }
            val afterDelete = _uiState.value as? ProfileUiState.Content ?: return@launch
            when (result) {
                is ApiResult.Success -> {
                    _uiState.value = afterDelete.copy(
                        accountDeletionState = AccountDeletionState.Deleted
                    )
                    _profileEvents.emit(ProfileEvent.AccountDeleted)
                }
                is ApiResult.Error -> {
                    _uiState.value = afterDelete.copy(
                        accountDeletionState = AccountDeletionState.Failed(result.error.message)
                    )
                }
                else -> {
                    _uiState.value = afterDelete.copy(
                        accountDeletionState = AccountDeletionState.Failed(
                            "Account deletion failed. Please try again."
                        )
                    )
                }
            }
        }
    }

    /**
     * Resets [AccountDeletionState] back to [AccountDeletionState.Idle] from a
     * [AccountDeletionState.Failed] state so the user can retry.
     */
    fun dismissDeletionError() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        _uiState.value = current.copy(accountDeletionState = AccountDeletionState.Idle)
    }

    // ─── General error handling ───────────────────────────────────────────────

    /**
     * Clears the transient [ProfileUiState.Content.errorMessage] after the user dismisses it.
     */
    fun dismissError() {
        val current = _uiState.value as? ProfileUiState.Content ?: return
        _uiState.value = current.copy(errorMessage = null)
    }

    /**
     * Resets state to [ProfileUiState.Loading] and re-collects both data flows.
     * Used after a critical error to retry loading profile data.
     */
    fun retry() {
        _uiState.value = ProfileUiState.Loading
        observeProfile()
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        /** The exact phrase the user must type to confirm account deletion. */
        const val DELETION_CONFIRMATION_PHRASE = "DELETE"
    }
}

/**
 * One-shot events emitted by [ProfileViewModel] that require navigation or system-level
 * side effects outside the composable's scope.
 */
sealed class ProfileEvent {
    /**
     * The account deletion was accepted by the backend. The caller should clear all local
     * data and navigate to the authentication screen.
     */
    data object AccountDeleted : ProfileEvent()
}

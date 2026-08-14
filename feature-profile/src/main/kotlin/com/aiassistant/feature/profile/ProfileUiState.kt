/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-profile
 * File       : ProfileUiState.kt
 * Purpose    : ProfileUiState — feature-profile module component
 *
 * Architecture Layer : Feature (feature-profile)
 * Pattern Used       : UI State Data Class
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
 * ProfileUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the profile feature,
 *          including loading, the main profile content with memory list, profile editing,
 *          data export, and account deletion flows.
 * Architecture: feature-profile — MVVM presentation layer.
 * Dependencies: domain (User, Memory, MemoryType)
 *
 * Design decisions:
 * - [Content] holds user profile, memory list, and all transient dialog/action states in
 *   a single coherent snapshot so the UI never renders a partially-loaded state.
 * - [deletingMemoryIds] tracks in-flight deletes at the individual row level so each
 *   memory card can show its own loading indicator independently.
 * - [editingMemory] + [editContent] model the inline memory edit dialog as pure state.
 * - [isEditingName] + [editingName] model the profile name-edit dialog as pure state.
 * - [dataExportStatus] captures the export request lifecycle (idle → requesting → done/error).
 * - [accountDeletionState] captures the multi-step deletion flow (idle → confirming → deleting → done/error).
 * - [errorMessage] is transient — cleared by [ProfileViewModel.dismissError] after acknowledgment.
 *
 * Requirements: 7.3, 7.4, 28.1, 28.2
 */
package com.aiassistant.feature.profile

import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.model.MemoryType
import com.aiassistant.domain.model.User

// ─── Memory type label helper ──────────────────────────────────────────────────

/**
 * Maps a [MemoryType] to its human-readable display string.
 *
 * @param type The [MemoryType] to convert.
 * @return A human-readable label for the memory type.
 */
fun memoryTypeLabel(type: MemoryType): String = when (type) {
    MemoryType.PREFERENCE -> "Preference"
    MemoryType.FACT -> "Fact"
    MemoryType.STYLE -> "Writing Style"
}

// ─── Data export status ────────────────────────────────────────────────────────

/**
 * Represents the current status of a data export request (Requirement 28.1).
 */
sealed class DataExportStatus {
    /** No export has been requested in this session. */
    data object Idle : DataExportStatus()

    /** The export POST request is in-flight. */
    data object Requesting : DataExportStatus()

    /**
     * The export request was accepted by the backend.
     * The data archive will be ready within 24 hours.
     */
    data object Requested : DataExportStatus()

    /** The export request failed. */
    data class Failed(val message: String) : DataExportStatus()
}

// ─── Account deletion state ────────────────────────────────────────────────────

/**
 * Represents the current state of the account deletion flow (Requirement 28.2).
 */
sealed class AccountDeletionState {
    /** Deletion has not been initiated. */
    data object Idle : AccountDeletionState()

    /**
     * The confirmation dialog is open. The user must type "DELETE" to enable the button.
     *
     * @param confirmationInput The current text in the confirmation text field.
     */
    data class Confirming(val confirmationInput: String = "") : AccountDeletionState()

    /** The DELETE request is in-flight to the backend. */
    data object Deleting : AccountDeletionState()

    /**
     * The deletion request was accepted. Local data has been cleared; the caller
     * should navigate to the authentication screen.
     */
    data object Deleted : AccountDeletionState()

    /** The deletion request failed. */
    data class Failed(val message: String) : AccountDeletionState()
}

// ─── Sealed state class ────────────────────────────────────────────────────────

/**
 * Represents every possible UI state in the profile feature.
 *
 * The [ProfileViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Composables observe it and render accordingly.
 */
sealed class ProfileUiState {

    /** Initial data load is in progress. */
    data object Loading : ProfileUiState()

    /**
     * The profile screen is ready and displaying current data.
     *
     * @param user                User profile (nullable during initial load).
     * @param memories            Full list of memories stored for this user.
     * @param deletingMemoryIds   IDs currently being deleted — shows per-row spinner.
     * @param editingMemory       Memory open in the edit dialog, null when closed.
     * @param editContent         Draft text in the memory edit dialog.
     * @param isSavingEdit        True while the memory update is in-flight.
     * @param isEditingName       True while the edit-name dialog is open.
     * @param editingName         Draft display name in the edit-name dialog.
     * @param isSavingName        True while the display-name update is in-flight.
     * @param dataExportStatus    Current state of the data export request (Requirement 28.1).
     * @param accountDeletionState Current state of the account deletion flow (Requirement 28.2).
     * @param errorMessage        Transient error message for the snackbar; null = no pending error.
     */
    data class Content(
        val user: User? = null,
        val memories: List<Memory> = emptyList(),
        val deletingMemoryIds: Set<String> = emptySet(),
        // Memory edit dialog
        val editingMemory: Memory? = null,
        val editContent: String = "",
        val isSavingEdit: Boolean = false,
        // Name edit dialog
        val isEditingName: Boolean = false,
        val editingName: String = "",
        val isSavingName: Boolean = false,
        // Data export (Requirement 28.1)
        val dataExportStatus: DataExportStatus = DataExportStatus.Idle,
        // Account deletion (Requirement 28.2)
        val accountDeletionState: AccountDeletionState = AccountDeletionState.Idle,
        // Transient error
        val errorMessage: String? = null
    ) : ProfileUiState()

    /**
     * A critical load failure occurred.
     *
     * @param message Human-readable error description shown with a Retry button.
     */
    data class Error(val message: String) : ProfileUiState()
}

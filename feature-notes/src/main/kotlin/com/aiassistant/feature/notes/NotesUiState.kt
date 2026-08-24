/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-notes
 * File       : NotesUiState.kt
 * Purpose    : NotesUiState — feature-notes module component
 *
 * Architecture Layer : Feature (feature-notes)
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

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-notes
 * File       : NotesUiState.kt
 * Purpose    : NotesUiState — feature-notes module component
 *
 * Architecture Layer : Feature (feature-notes)
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
 * NotesUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the notes feature,
 *          including list, editor, AI processing, AI result, loading, and error states.
 * Architecture: feature-notes â€” MVVM presentation layer.
 * Dependencies: domain (Note)
 *
 * Requirements: 13.1, 13.2, 13.3, 13.5
 */
package com.aiassistant.feature.notes

import com.aiassistant.domain.model.Note

/**
 * Identifies which AI operation is currently in progress or has produced a result.
 */
enum class AiOperation {
    /** AI-generated summary (â‰¤ 150 words) of the note content (Requirement 13.2). */
    SUMMARIZE,

    /** AI rewrite of the note in the user's learned or neutral professional style (Requirement 13.3). */
    REWRITE
}

/**
 * Represents every possible UI state in the notes feature.
 *
 * The [NotesViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Composables observe it and render accordingly.
 */
sealed class NotesUiState {

    /** A data load or navigation transition is in progress. */
    data object Loading : NotesUiState()

    /**
     * The notes list screen is active.
     *
     * @param notes       The full list of notes for the authenticated user.
     * @param selectedTag The currently applied tag filter. Empty string means "All".
     * @param allTags     Distinct tags gathered from all notes, used to populate filter chips.
     */
    data class NotesList(val notes: List<Note>, val selectedTag: String = "", val allTags: List<String> = emptyList()) :
        NotesUiState()

    /**
     * The note editor screen is active.
     *
     * @param note               The note being viewed or edited.
     * @param isNew              True when creating a new note; false when editing an existing one.
     * @param isSaving           True while the save operation is in progress.
     * @param previewMode        True when the content tab is showing the rendered Markdown preview.
     * @param contextSuggestions Context-aware AI suggestions displayed as chips above the keyboard (Requirement 33.1).
     */
    data class NoteEditor(
        val note: Note,
        val isNew: Boolean,
        val isSaving: Boolean = false,
        val previewMode: Boolean = false,
        val contextSuggestions: List<com.aiassistant.domain.model.ContextSuggestion> = emptyList()
    ) : NotesUiState()

    /**
     * An AI operation (summarise or rewrite) is in progress.
     *
     * @param noteId    The identifier of the note being processed.
     * @param operation Which AI operation is running.
     * @param note      The note snapshot at the time the operation was requested.
     */
    data class AiProcessing(val noteId: String, val operation: AiOperation, val note: Note) : NotesUiState()

    /**
     * An AI operation completed successfully.
     *
     * @param noteId    The identifier of the processed note.
     * @param operation Which AI operation produced the result.
     * @param result    The AI-generated text (summary or rewritten content).
     * @param note      The note snapshot to which the result may be applied.
     */
    data class AiResult(val noteId: String, val operation: AiOperation, val result: String, val note: Note) :
        NotesUiState()

    /**
     * An operation failed.
     *
     * @param message Human-readable error message for the error banner.
     */
    data class Error(val message: String) : NotesUiState()
}

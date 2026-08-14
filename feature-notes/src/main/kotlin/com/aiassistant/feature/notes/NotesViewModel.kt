/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-notes
 * File       : NotesViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Notes feature
 *
 * Architecture Layer : Feature (feature-notes)
 * Pattern Used       : MVVM ViewModel
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
 * File       : NotesViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Notes feature
 *
 * Architecture Layer : Feature (feature-notes)
 * Pattern Used       : MVVM ViewModel
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
 * NotesViewModel.kt
 *
 * Purpose: Manages all UI state and orchestrates use case calls for the notes feature,
 *          including listing, filtering, editing, saving, deleting, and AI operations.
 * Architecture: feature-notes â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (SaveNoteUseCase, SummarizeNoteUseCase, RewriteNoteUseCase, NoteRepository),
 *               core-common (DispatcherProvider, ApiResult)
 *
 * Requirements: 13.1, 13.2, 13.3, 13.4, 13.5
 */
package com.aiassistant.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.ScreenContext
import com.aiassistant.domain.model.SuggestionType
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.repository.NoteRepository
import com.aiassistant.domain.usecase.note.RewriteNoteUseCase
import com.aiassistant.domain.usecase.note.SaveNoteUseCase
import com.aiassistant.domain.usecase.note.SummarizeNoteUseCase
import com.aiassistant.domain.usecase.suggestions.DismissSuggestionUseCase
import com.aiassistant.domain.usecase.suggestions.GetContextSuggestionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ViewModel for the notes list and note editor flows.
 *
 * Exposes a [StateFlow] of [NotesUiState] that composables observe. All blocking work
 * (network calls, database operations) is dispatched on [DispatcherProvider.io].
 */
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val saveNoteUseCase: SaveNoteUseCase,
    private val summarizeNoteUseCase: SummarizeNoteUseCase,
    private val rewriteNoteUseCase: RewriteNoteUseCase,
    private val noteRepository: NoteRepository,
    private val dispatchers: DispatcherProvider,
    private val getContextSuggestionsUseCase: GetContextSuggestionsUseCase,
    private val dismissSuggestionUseCase: DismissSuggestionUseCase
) : ViewModel() {

    // ─── Suggestion settings (updated by Settings screen) ────────────────────
    private var isSuggestionsEnabled: Boolean = true
    private var isPrivacyModeEnabled: Boolean = false

    // â”€â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<NotesUiState>(NotesUiState.Loading)

    /** Observable notes UI state. */
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Init â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    init {
        loadNotes()
    }

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Loads all notes for the current user and emits [NotesUiState.NotesList].
     *
     * Collects the first emission of [NoteRepository.getNotes] and derives the full
     * tag list from the returned notes. Subsequent emissions update state in place.
     */
    fun loadNotes() {
        viewModelScope.launch {
            _uiState.value = NotesUiState.Loading
            noteRepository.getNotes().collect { result ->
                _uiState.value = when (result) {
                    is ApiResult.Success -> {
                        val notes = result.data
                        val allTags = notes.flatMap { it.tags }.distinct().sorted()
                        NotesUiState.NotesList(notes = notes, allTags = allTags)
                    }
                    is ApiResult.Error -> NotesUiState.Error(result.error.message)
                    is ApiResult.NetworkUnavailable -> {
                        // Emit empty list â€” offline-first; cached data shown separately
                        NotesUiState.NotesList(notes = emptyList())
                    }
                    is ApiResult.Loading -> NotesUiState.Loading
                }
            }
        }
    }

    /**
     * Applies a tag filter to the notes list.
     *
     * An empty [tag] clears the filter and loads all notes. Otherwise, loads notes
     * tagged with [tag] (Requirement 13.5).
     *
     * @param tag The tag label to filter by, or an empty string for "All".
     */
    fun selectTagFilter(tag: String) {
        viewModelScope.launch {
            _uiState.value = NotesUiState.Loading
            val flow = if (tag.isBlank()) {
                noteRepository.getNotes()
            } else {
                noteRepository.getNotesByTag(tag)
            }
            flow.collect { result ->
                _uiState.value = when (result) {
                    is ApiResult.Success -> {
                        val notes = result.data
                        // Preserve allTags from current state if available
                        val currentAllTags = (uiState.value as? NotesUiState.NotesList)
                            ?.allTags ?: emptyList()
                        NotesUiState.NotesList(
                            notes = notes,
                            selectedTag = tag,
                            allTags = currentAllTags
                        )
                    }
                    is ApiResult.Error -> NotesUiState.Error(result.error.message)
                    is ApiResult.NetworkUnavailable -> NotesUiState.NotesList(
                        notes = emptyList(),
                        selectedTag = tag
                    )
                    is ApiResult.Loading -> NotesUiState.Loading
                }
            }
        }
    }

    /**
     * Transitions to [NotesUiState.NoteEditor] for a new, empty note.
     *
     * The new note has a fresh UUID id, empty title/content, no tags, and
     * [SyncStatus.PENDING] â€” ready for the user to fill in.
     */
    fun openNewNote() {
        val now = Instant.now().toEpochMilli()
        val emptyNote = Note(
            id = UUID.randomUUID().toString(),
            userId = "",
            title = "",
            content = "",
            tags = emptyList(),
            syncStatus = SyncStatus.PENDING,
            createdAt = now,
            updatedAt = now
        )
        _uiState.value = NotesUiState.NoteEditor(note = emptyNote, isNew = true)
    }

    /**
     * Transitions to [NotesUiState.NoteEditor] for an existing note.
     *
     * @param note The note to open for editing.
     */
    fun openNote(note: Note) {
        _uiState.value = NotesUiState.NoteEditor(note = note, isNew = false)
    }

    /**
     * Updates the draft note in the [NotesUiState.NoteEditor] state without persisting.
     *
     * No repository call is made â€” changes are buffered in state until the user
     * explicitly saves (Requirement 13.1).
     *
     * @param title   Updated note title.
     * @param content Updated note body (plain text or Markdown).
     * @param tags    Updated tag list.
     */
    fun updateDraft(title: String, content: String, tags: List<String>) {
        val currentState = _uiState.value as? NotesUiState.NoteEditor ?: return
        _uiState.value = currentState.copy(
            note = currentState.note.copy(
                title = title,
                content = content,
                tags = tags,
                updatedAt = Instant.now().toEpochMilli()
            )
        )
    }

    /**
     * Persists the [note] via [SaveNoteUseCase] and returns to the notes list on success.
     *
     * Sets [NotesUiState.NoteEditor.isSaving] to true while the operation is in progress.
     * On success transitions to [NotesUiState.NotesList] by calling [loadNotes].
     * On failure emits [NotesUiState.Error] (Requirement 13.4).
     *
     * @param note The note (with current draft content) to persist.
     */
    fun saveNote(note: Note) {
        val currentState = _uiState.value as? NotesUiState.NoteEditor ?: return
        _uiState.value = currentState.copy(isSaving = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) { saveNoteUseCase(note) }
            when (result) {
                is ApiResult.Success -> loadNotes()
                is ApiResult.Error -> _uiState.value = NotesUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> _uiState.value = NotesUiState.Error(
                    "No network connection. Note will sync when you're back online."
                )
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Permanently deletes the note identified by [noteId] and refreshes the list.
     *
     * @param noteId The unique identifier of the note to delete.
     */
    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) { noteRepository.deleteNote(noteId) }
            loadNotes()
        }
    }

    /**
     * Toggles the Markdown preview mode in [NotesUiState.NoteEditor].
     *
     * No-op when the current state is not [NotesUiState.NoteEditor].
     */
    fun togglePreviewMode() {
        val currentState = _uiState.value as? NotesUiState.NoteEditor ?: return
        _uiState.value = currentState.copy(previewMode = !currentState.previewMode)
    }

    /**
     * Requests an AI summary for the note identified by [noteId] (Requirement 13.2).
     *
     * Transitions through [NotesUiState.AiProcessing] then to [NotesUiState.AiResult]
     * on success, or [NotesUiState.Error] on failure.
     *
     * @param noteId The unique identifier of the note to summarise.
     */
    fun summarizeNote(noteId: String) {
        val note = currentNote(noteId) ?: return
        _uiState.value = NotesUiState.AiProcessing(noteId, AiOperation.SUMMARIZE, note)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) { summarizeNoteUseCase(noteId) }
            _uiState.value = when (result) {
                is ApiResult.Success -> NotesUiState.AiResult(
                    noteId = noteId,
                    operation = AiOperation.SUMMARIZE,
                    result = result.data,
                    note = note
                )
                is ApiResult.Error -> NotesUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> NotesUiState.Error(
                    "No network connection. AI features require internet access."
                )
                is ApiResult.Loading -> NotesUiState.AiProcessing(noteId, AiOperation.SUMMARIZE, note)
            }
        }
    }

    /**
     * Requests an AI rewrite for the note identified by [noteId] (Requirement 13.3).
     *
     * Transitions through [NotesUiState.AiProcessing] then to [NotesUiState.AiResult]
     * on success, or [NotesUiState.Error] on failure.
     *
     * @param noteId The unique identifier of the note to rewrite.
     */
    fun rewriteNote(noteId: String) {
        val note = currentNote(noteId) ?: return
        _uiState.value = NotesUiState.AiProcessing(noteId, AiOperation.REWRITE, note)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) { rewriteNoteUseCase(noteId) }
            _uiState.value = when (result) {
                is ApiResult.Success -> NotesUiState.AiResult(
                    noteId = noteId,
                    operation = AiOperation.REWRITE,
                    result = result.data,
                    note = note
                )
                is ApiResult.Error -> NotesUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> NotesUiState.Error(
                    "No network connection. AI features require internet access."
                )
                is ApiResult.Loading -> NotesUiState.AiProcessing(noteId, AiOperation.REWRITE, note)
            }
        }
    }

    /**
     * Applies the AI-generated [result] to the note's content and transitions back to
     * [NotesUiState.NoteEditor].
     *
     * Only valid when the current state is [NotesUiState.AiResult].
     *
     * @param result The AI-generated text to apply as the note's new content.
     */
    fun applyAiResult(result: String) {
        val currentState = _uiState.value as? NotesUiState.AiResult ?: return
        val updatedNote = currentState.note.copy(
            content = result,
            updatedAt = Instant.now().toEpochMilli()
        )
        _uiState.value = NotesUiState.NoteEditor(
            note = updatedNote,
            isNew = false,
            previewMode = false
        )
    }

    /**
     * Dismisses the AI result dialog and returns to [NotesUiState.NoteEditor] without
     * applying the generated content.
     */
    fun dismissAiResult() {
        val currentState = _uiState.value as? NotesUiState.AiResult ?: return
        _uiState.value = NotesUiState.NoteEditor(
            note = currentState.note,
            isNew = false
        )
    }

    /**
     * Navigates back to the notes list by calling [loadNotes].
     */
    fun backToList() {
        loadNotes()
    }

    // ─── Context suggestion methods (Requirement 33.1, 33.5, 33.8) ───────────

    /**
     * Updates the enabled state for global context suggestions (Requirement 33.8).
     *
     * Call this from the Settings screen when the user toggles the suggestions switch.
     *
     * @param enabled `false` when the user has disabled suggestions globally.
     */
    fun updateSuggestionsEnabled(enabled: Boolean) {
        isSuggestionsEnabled = enabled
    }

    /**
     * Updates the privacy mode flag (Requirement 33.7).
     *
     * When [enabled] is `true`, context suggestions are suppressed without deleting
     * any stored data.
     *
     * @param enabled `true` when privacy mode is active.
     */
    fun updatePrivacyMode(enabled: Boolean) {
        isPrivacyModeEnabled = enabled
    }

    /**
     * Requests context-aware suggestions for the note identified by [noteId] with the
     * given [noteContent] (Requirement 33.1).
     *
     * - Enforces a 3-second timeout; silently leaves suggestions empty on timeout.
     * - Calls [GetContextSuggestionsUseCase] with [ScreenContext.NoteContext].
     * - Already-dismissed suggestion types are filtered from the result before updating state.
     * - No loading indicator is shown while the request is in-flight.
     *
     * @param noteId      The ID of the note being edited (used as screenInstanceId for
     *                    rate-gating and dismissal tracking).
     * @param noteContent Current text content of the note.
     */
    fun requestContextSuggestions(noteId: String, noteContent: String) {
        val currentState = _uiState.value as? NotesUiState.NoteEditor ?: return

        viewModelScope.launch {
            val context = ScreenContext.NoteContext(
                noteContent = noteContent,
                screenInstanceId = noteId
            )
            // 3-second timeout — no loading indicator shown (Requirement 33.6)
            val result = withTimeoutOrNull(3_000L) {
                withContext(dispatchers.io) {
                    getContextSuggestionsUseCase(
                        context = context,
                        isPrivacyModeEnabled = isPrivacyModeEnabled,
                        isSuggestionsEnabled = isSuggestionsEnabled
                    )
                }
            }

            val suggestions = when (result) {
                is ApiResult.Success -> result.data.filter { suggestion ->
                    !dismissSuggestionUseCase.isDismissed(noteId, suggestion.type)
                }
                else -> return@launch // timeout or error — leave suggestions unchanged
            }

            // Only update if still in NoteEditor state for the same note
            val latest = _uiState.value as? NotesUiState.NoteEditor ?: return@launch
            if (latest.note.id == noteId) {
                _uiState.value = latest.copy(contextSuggestions = suggestions)
            }
        }
    }

    /**
     * Records a suggestion dismissal and removes that suggestion type from the current
     * editor state (Requirement 33.5).
     *
     * The dismissal is session-scoped: the suppressed type will not reappear on this
     * screen instance for the remainder of the session.
     *
     * @param noteId The ID of the note screen instance.
     * @param type   The [SuggestionType] that was dismissed.
     */
    fun dismissSuggestion(noteId: String, type: SuggestionType) {
        dismissSuggestionUseCase.invoke(noteId, type)
        val currentState = _uiState.value as? NotesUiState.NoteEditor ?: return
        _uiState.value = currentState.copy(
            contextSuggestions = currentState.contextSuggestions.filter { it.type != type }
        )
    }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns the note with the given [noteId] from the current state if available.
     */
    private fun currentNote(noteId: String): Note? = when (val state = _uiState.value) {
        is NotesUiState.NoteEditor -> state.note.takeIf { it.id == noteId }
        is NotesUiState.NotesList -> state.notes.firstOrNull { it.id == noteId }
        else -> null
    }
}

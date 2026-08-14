/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SaveNoteUseCase.kt
 * Purpose    : Encapsulates the 'SaveNote' business operation
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

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SaveNoteUseCase.kt
 * Purpose    : Encapsulates the 'SaveNote' business operation
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
 * SaveNoteUseCase.kt
 *
 * Purpose: Persists a new or updated note locally and queues a backend sync.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), NoteRepository, Note
 *
 * Requirements: 13.1, 13.4
 */

package com.aiassistant.domain.usecase.note

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.repository.NoteRepository
import javax.inject.Inject

/**
 * Use case for creating or updating a note.
 *
 * THE AI_Assistant SHALL provide a notes editor supporting plain text and Markdown input
 * (Requirement 13.1). THE AI_Assistant SHALL persist notes in the local Room database and
 * synchronise them with the Backend when connectivity is available (Requirement 13.4).
 *
 * @param noteRepository Repository providing the note persistence operation.
 */
class SaveNoteUseCase @Inject constructor(private val noteRepository: NoteRepository) {

    /**
     * Persists the given [note].
     *
     * Validates that the note title is not blank before delegating to the repository.
     *
     * @param note The note to create or update.
     * @return [ApiResult.Success] with the persisted [Note] on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if the title is blank.
     */
    suspend operator fun invoke(note: Note): ApiResult<Note> {
        if (note.title.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Note title must not be blank.",
                    fields = mapOf(FIELD_TITLE to "A non-empty title is required.")
                )
            )
        }

        return noteRepository.saveNote(note)
    }

    internal companion object {
        const val FIELD_TITLE = "title"
    }
}

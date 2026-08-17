/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : RewriteNoteUseCase.kt
 * Purpose    : Encapsulates the 'RewriteNote' business operation
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
 * RewriteNoteUseCase.kt
 *
 * Purpose: Requests an AI rewrite of a note in the user's learned writing style or
 *          neutral professional style as fallback.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), NoteRepository
 *
 * Requirements: 13.3
 */

package com.aiassistant.domain.usecase.note

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.NoteRepository
import javax.inject.Inject

/**
 * Use case for requesting an AI rewrite of a note.
 *
 * WHEN a User requests an AI rewrite of a note, THE AI_Orchestrator SHALL return a
 * rewritten version in the User's previously learned writing style if Memory_Service
 * records are available, or in a neutral professional style otherwise (Requirement 13.3).
 *
 * Style selection is handled at the backend level; this use case delegates directly
 * to the repository.
 *
 * @param noteRepository Repository providing the AI rewrite operation.
 */
class RewriteNoteUseCase @Inject constructor(private val noteRepository: NoteRepository) {

    /**
     * Requests an AI rewrite for the given note.
     *
     * @param noteId The unique identifier of the note to rewrite.
     * @return [ApiResult.Success] with the rewritten content on success.
     */
    suspend operator fun invoke(noteId: String): ApiResult<String> = noteRepository.rewriteNote(noteId)
}

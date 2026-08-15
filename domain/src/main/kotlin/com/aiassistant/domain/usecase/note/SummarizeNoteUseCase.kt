/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SummarizeNoteUseCase.kt
 * Purpose    : Encapsulates the 'SummarizeNote' business operation
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
 * SummarizeNoteUseCase.kt
 *
 * Purpose: Requests an AI-generated summary of a note's content (â‰¤ 150 words).
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), NoteRepository
 *
 * Requirements: 13.2
 */

package com.aiassistant.domain.usecase.note

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.NoteRepository
import javax.inject.Inject

/**
 * Use case for generating an AI summary of a note.
 *
 * WHEN a User requests an AI summary of a note, THE AI_Orchestrator SHALL return a
 * concise summary of no more than 150 words preserving all key facts. IF the generated
 * summary exceeds 150 words, THE AI_Orchestrator SHALL truncate it to exactly 150 words
 * before delivering it to the User (Requirement 13.2).
 *
 * The word-count constraint is enforced at the backend level; this use case delegates
 * directly to the repository.
 *
 * @param noteRepository Repository providing the AI summarise operation.
 */
class SummarizeNoteUseCase @Inject constructor(private val noteRepository: NoteRepository) {

    /**
     * Requests an AI summary for the given note.
     *
     * @param noteId The unique identifier of the note to summarise.
     * @return [ApiResult.Success] with the summary text (â‰¤ 150 words) on success.
     */
    suspend operator fun invoke(noteId: String): ApiResult<String> = noteRepository.summarizeNote(noteId)
}

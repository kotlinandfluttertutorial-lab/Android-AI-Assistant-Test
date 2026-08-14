/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ExportConversationUseCase.kt
 * Purpose    : Encapsulates the 'ExportConversation' business operation
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
 * File       : ExportConversationUseCase.kt
 * Purpose    : Encapsulates the 'ExportConversation' business operation
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
 * ExportConversationUseCase.kt
 *
 * Purpose: Exports a conversation in the requested format (Markdown or PDF), returning
 *          the file path or content string of the exported output.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain repository (ConversationRepository),
 *               domain model (ExportFormat)
 *
 * Requirements: 11.6, 2.7
 *
 * Design decisions:
 * - The domain layer performs no file I/O; the repository implementation is responsible
 *   for generating the Markdown content or the PDF file and returning the result path/string.
 * - A single suspend call keeps the use case surface area minimal.
 */

package com.aiassistant.domain.usecase.conversation

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.ExportFormat
import com.aiassistant.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Use case for exporting a conversation as Markdown or PDF.
 *
 * THE AI_Assistant SHALL allow the User to export a Conversation as a Markdown or PDF file
 * (Requirement 11.6). THE AI_Assistant SHALL allow the User to copy, share, or export any
 * Message as plain text or Markdown (Requirement 2.7).
 *
 * @param conversationRepository Repository providing the export operation.
 */
class ExportConversationUseCase @Inject constructor(private val conversationRepository: ConversationRepository) {

    /**
     * Executes the export operation.
     *
     * Delegates to [ConversationRepository.exportConversation]. The data layer assembles
     * the conversation messages and either:
     * - Returns the Markdown-formatted content string for [ExportFormat.MARKDOWN].
     * - Generates a PDF file and returns its absolute path for [ExportFormat.PDF].
     *
     * @param conversationId The unique identifier of the conversation to export.
     * @param format         The desired export format. See [ExportFormat].
     * @return [ApiResult.Success] with the Markdown content string or absolute PDF file path,
     *         [ApiResult.Error] when the export operation fails,
     *         [ApiResult.NetworkUnavailable] when the device has no connectivity and the
     *         repository requires a network call for export generation.
     */
    suspend operator fun invoke(conversationId: String, format: ExportFormat): ApiResult<String> =
        conversationRepository.exportConversation(
            conversationId = conversationId,
            format = format
        )
}

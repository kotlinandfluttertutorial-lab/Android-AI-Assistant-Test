/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ExportFormat.kt
 * Purpose    : ExportFormat — domain module component
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Class
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
 * File       : ExportFormat.kt
 * Purpose    : ExportFormat — domain module component
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Class
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
 * ExportFormat.kt
 *
 * Purpose: Enum defining the supported export formats for conversations and messages.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 11.6, 2.7
 */

package com.aiassistant.domain.model

/**
 * Supported file formats for exporting a [Conversation] or individual [Message].
 *
 * THE AI_Assistant SHALL allow the User to export a Conversation as a Markdown or PDF file
 * (Requirement 11.6). THE AI_Assistant SHALL allow the User to copy, share, or export any
 * Message as plain text or Markdown (Requirement 2.7).
 */
enum class ExportFormat {
    /** Export as a plain Markdown (.md) text file. */
    MARKDOWN,

    /** Export as a formatted PDF document. */
    PDF
}

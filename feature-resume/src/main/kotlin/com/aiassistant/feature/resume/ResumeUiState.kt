/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-resume
 * File       : ResumeUiState.kt
 * Purpose    : ResumeUiState — feature-resume module component
 *
 * Architecture Layer : Feature (feature-resume)
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
 * Module     : feature-resume
 * File       : ResumeUiState.kt
 * Purpose    : ResumeUiState — feature-resume module component
 *
 * Architecture Layer : Feature (feature-resume)
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
 * ResumeUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the resume and
 *          cover letter generation flow.
 * Architecture: feature-resume â€” MVVM presentation layer.
 * Dependencies: None (pure Kotlin)
 *
 * Requirements: 14.1, 14.2, 14.3
 */
package com.aiassistant.feature.resume

/**
 * Supported export formats for generated resume or cover letter content.
 *
 * PDF and DOCX are required by Requirement 14.3.
 */
enum class ResumeExportFormat {
    /** Export as a formatted PDF document. */
    PDF,

    /** Export as a Microsoft Word DOCX document. */
    DOCX
}

/**
 * Represents every possible UI state in the resume / cover letter generation flow.
 *
 * The [ResumeViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Composables observe it and render accordingly.
 */
sealed class ResumeUiState {

    /** Initial state before any operation has started. */
    data object Idle : ResumeUiState()

    /**
     * An AI generation operation is in progress.
     *
     * @param message Human-readable progress message shown alongside the spinner
     *                (e.g., "Generating resumeâ€¦" or "Generating cover letterâ€¦").
     */
    data class Loading(val message: String) : ResumeUiState()

    /**
     * Resume generation succeeded.
     *
     * @param resumeMarkdown The ATS-optimised resume in Markdown format (Requirement 14.1).
     */
    data class ResumeGenerated(val resumeMarkdown: String) : ResumeUiState()

    /**
     * Cover letter generation succeeded.
     *
     * @param coverLetterText The tailored cover letter text (â‰¤ 400 words, Requirement 14.2).
     */
    data class CoverLetterGenerated(val coverLetterText: String) : ResumeUiState()

    /** An export operation is in progress (Requirement 14.3). */
    data object Exporting : ResumeUiState()

    /**
     * Export completed successfully.
     *
     * @param filePath Absolute path of the exported file on the device.
     * @param format   The format in which the content was exported.
     */
    data class ExportSuccess(val filePath: String, val format: ResumeExportFormat) : ResumeUiState()

    /**
     * An operation failed.
     *
     * @param message Human-readable error message for the error banner.
     */
    data class Error(val message: String) : ResumeUiState()
}

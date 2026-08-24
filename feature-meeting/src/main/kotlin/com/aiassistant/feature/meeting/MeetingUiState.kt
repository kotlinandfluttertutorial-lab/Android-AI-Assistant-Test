/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-meeting
 * File       : MeetingUiState.kt
 * Purpose    : MeetingUiState — feature-meeting module component
 *
 * Architecture Layer : Feature (feature-meeting)
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
 * Module     : feature-meeting
 * File       : MeetingUiState.kt
 * Purpose    : MeetingUiState — feature-meeting module component
 *
 * Architecture Layer : Feature (feature-meeting)
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
 * MeetingUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the Meeting Recorder
 *          feature. Drives both MeetingRecorderScreen and MeetingSummaryScreen.
 * Architecture: feature-meeting â€” MVVM presentation layer; consumed by composable screens.
 * Dependencies: None (pure Kotlin)
 *
 * Requirements: 19.1, 5.6
 *
 * Design decisions:
 * - State machine mirrors the recording lifecycle: Idle â†’ Recording â†’ Processing â†’ Complete.
 * - PermissionDenied is a terminal state that requires the user to visit system settings
 *   (Requirement 5.6).
 * - ActionItem is a value class nested alongside the state so the domain stays clean.
 * - Action items are parsed from the AI summary text by the ViewModel (not the domain).
 */
package com.aiassistant.feature.meeting

/**
 * A single actionable task extracted from the meeting transcript.
 *
 * @param assignee    Name of the participant assigned to this task.
 * @param description Human-readable description of the task.
 */
data class ActionItem(val assignee: String, val description: String)

/**
 * Represents every possible UI state in the Meeting Recorder / Summary flow.
 *
 * The [MeetingViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Both MeetingRecorderScreen and MeetingSummaryScreen observe it and render the
 * appropriate UI.
 *
 * State machine:
 * ```
 * Idle â”€â”€requestPermission()â”€â”€â–º RequestingPermission
 *                                     â”‚ granted
 *                                     â–¼
 *                               Idle (ready)
 *                                     â”‚ startRecording()
 *                                     â–¼
 *                               Recording â”€â”€stopRecording()â”€â”€â–º Processing â”€â”€fetchSummary()â”€â”€â–º Complete
 *                                                                                â”‚ error
 *                                                                                â–¼
 *                                                                              Error
 * ```
 */
sealed class MeetingUiState {

    /**
     * No active session. A "Start Recording" FAB is shown.
     */
    data object Idle : MeetingUiState()

    /**
     * The app is prompting for RECORD_AUDIO permission.
     * The rationale dialog is shown from this state (Requirement 5.6).
     */
    data object RequestingPermission : MeetingUiState()

    /**
     * The user denied the RECORD_AUDIO permission.
     * A settings deep-link is shown (Requirement 5.6).
     */
    data object PermissionDenied : MeetingUiState()

    /**
     * A session is in progress and the microphone is active.
     *
     * @param durationSeconds Elapsed recording time in seconds.
     * @param sessionId       Backend session identifier returned by StartMeetingRecordingUseCase.
     */
    data class Recording(val durationSeconds: Int, val sessionId: String) : MeetingUiState()

    /**
     * Audio has been submitted and the backend is transcribing / summarising.
     *
     * @param sessionId Session identifier used to poll for the completed summary.
     */
    data class Processing(val sessionId: String) : MeetingUiState()

    /**
     * Transcription and summarisation are complete.
     *
     * @param sessionId   Session identifier.
     * @param transcript  Raw timestamped transcript with speaker labels.
     * @param summary     AI-generated meeting summary (may contain Markdown).
     * @param actionItems Discrete tasks extracted from the transcript.
     */
    data class Complete(
        val sessionId: String,
        val transcript: String,
        val summary: String,
        val actionItems: List<ActionItem>
    ) : MeetingUiState()

    /**
     * A recoverable error occurred.
     *
     * @param message Human-readable error description shown to the user.
     */
    data class Error(val message: String) : MeetingUiState()
}

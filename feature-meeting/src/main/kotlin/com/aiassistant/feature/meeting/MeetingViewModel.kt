/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-meeting
 * File       : MeetingViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Meeting feature
 *
 * Architecture Layer : Feature (feature-meeting)
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
 * Module     : feature-meeting
 * File       : MeetingViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Meeting feature
 *
 * Architecture Layer : Feature (feature-meeting)
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
 * MeetingViewModel.kt
 *
 * Purpose: Manages the Meeting Recorder state machine and orchestrates calls to the
 *          meeting use cases. Owns no Android framework objects (no Context, MediaRecorder)
 *          â€” those live in MeetingRecorderManager.
 * Architecture: feature-meeting â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (StartMeetingRecordingUseCase, StopMeetingRecordingUseCase,
 *               GetMeetingSummaryUseCase), core-common (DispatcherProvider, ApiResult)
 *
 * Requirements: 19.1, 5.6
 *
 * Design decisions:
 * - ViewModel only manages state and use-case calls; Android-specific concerns (MediaRecorder)
 *   are fully isolated in MeetingRecorderManager to keep ViewModel testable.
 * - State machine is a simple StateFlow<MeetingUiState>; transitions are explicit methods.
 * - Export functions accept a Context parameter (valid for ViewModel helper methods that
 *   trigger share intents â€” the Context is provided by the composable, not stored).
 * - Action item parsing uses a regex matching "- [Assignee]: description" lines.
 * - All blocking work dispatched on dispatchers.io.
 */
package com.aiassistant.feature.meeting

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.usecase.meeting.GetMeetingSummaryUseCase
import com.aiassistant.domain.usecase.meeting.StartMeetingRecordingUseCase
import com.aiassistant.domain.usecase.meeting.StopMeetingRecordingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Meeting Recorder and Meeting Summary screens.
 *
 * Drives the state machine:
 * ```
 * Idle â”€â”€startRecording()â”€â”€â–º Recording â”€â”€stopRecording()â”€â”€â–º Processing â”€â”€fetchSummary()â”€â”€â–º Complete
 * ```
 * Permission states (RequestingPermission / PermissionDenied) branch from Idle.
 */
@HiltViewModel
class MeetingViewModel @Inject constructor(
    private val startMeetingRecordingUseCase: StartMeetingRecordingUseCase,
    private val stopMeetingRecordingUseCase: StopMeetingRecordingUseCase,
    private val getMeetingSummaryUseCase: GetMeetingSummaryUseCase,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<MeetingUiState>(MeetingUiState.Idle)

    /** Observable Meeting UI state. Never exposes the mutable backing field. */
    val uiState: StateFlow<MeetingUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Public API â€” permission â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Transitions Idle â†’ RequestingPermission.
     * The composable handles the actual system permission dialog.
     */
    fun requestPermission() {
        if (_uiState.value is MeetingUiState.Idle) {
            _uiState.value = MeetingUiState.RequestingPermission
        }
    }

    /**
     * Called when RECORD_AUDIO permission is granted.
     * Transitions RequestingPermission â†’ Idle (ready to record).
     */
    fun onPermissionGranted() {
        if (_uiState.value is MeetingUiState.RequestingPermission) {
            _uiState.value = MeetingUiState.Idle
        }
    }

    /**
     * Called when RECORD_AUDIO permission is denied (Requirement 5.6).
     * Transitions â†’ PermissionDenied.
     */
    fun onPermissionDenied() {
        _uiState.value = MeetingUiState.PermissionDenied
    }

    // â”€â”€â”€ Public API â€” recording â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Starts a meeting recording session.
     *
     * Transitions Idle â†’ Recording on success, or â†’ Error on failure.
     * The caller (composable) should also invoke MeetingRecorderManager.startRecording()
     * to open the microphone hardware.
     *
     * @param userId Authenticated user identifier for the session.
     */
    fun startRecording(userId: String) {
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                startMeetingRecordingUseCase(userId)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> MeetingUiState.Recording(
                    durationSeconds = 0,
                    sessionId = result.data
                )
                is ApiResult.Error -> MeetingUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> MeetingUiState.Error(
                    "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> _uiState.value // no-op
            }
        }
    }

    /**
     * Updates the recording duration counter.
     *
     * Called by a LaunchedEffect in the composable every second while in Recording state.
     *
     * @param seconds New elapsed time in seconds.
     */
    fun updateRecordingDuration(seconds: Int) {
        val current = _uiState.value
        if (current is MeetingUiState.Recording) {
            _uiState.value = current.copy(durationSeconds = seconds)
        }
    }

    /**
     * Stops the active recording session.
     *
     * Transitions Recording â†’ Processing on success, or â†’ Error on failure.
     * The caller (composable) should also invoke MeetingRecorderManager.stopRecording()
     * to close the microphone hardware before calling this method.
     */
    /**
     * Stops the active recording session and uploads the audio file for transcription.
     *
     * Transitions Recording → Processing on success, or → Error on failure.
     * The caller (composable) invokes [MeetingRecorderManager.stopRecording()] to close
     * the microphone hardware, then passes the returned file path to this method.
     *
     * @param audioFilePath Absolute path to the recorded audio file from
     *                      [MeetingRecorderManager.stopRecording].
     */
    fun stopRecording(audioFilePath: String) {
        val current = _uiState.value
        if (current !is MeetingUiState.Recording) return

        val sessionId = current.sessionId

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                stopMeetingRecordingUseCase(sessionId, audioFilePath)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> MeetingUiState.Processing(sessionId)
                is ApiResult.Error -> MeetingUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> MeetingUiState.Error(
                    "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> _uiState.value // no-op
            }
        }
    }

    /**
     * Fetches the AI-generated meeting summary.
     *
     * Transitions Processing â†’ Complete on success (with parsed action items),
     * or â†’ Error on failure.
     */
    fun fetchSummary() {
        val current = _uiState.value
        if (current !is MeetingUiState.Processing) return

        val sessionId = current.sessionId

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                getMeetingSummaryUseCase(sessionId)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> {
                    val summaryText = result.data
                    MeetingUiState.Complete(
                        sessionId = sessionId,
                        transcript = extractTranscript(summaryText),
                        summary = summaryText,
                        actionItems = parseActionItems(summaryText)
                    )
                }
                is ApiResult.Error -> MeetingUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> MeetingUiState.Error(
                    "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> _uiState.value // no-op
            }
        }
    }

    /**
     * Resets any error or terminal state back to Idle.
     */
    fun reset() {
        _uiState.value = MeetingUiState.Idle
    }

    // â”€â”€â”€ Export â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Shares the meeting summary as a PDF-compatible share intent.
     *
     * Uses [Intent.ACTION_SEND] with MIME type "application/pdf" to avoid requiring
     * WRITE_EXTERNAL_STORAGE permission. The receiving app handles PDF rendering.
     *
     * @param context Activity context required to start the intent.
     * @param summary The summary text to share.
     */
    fun exportAsPdf(context: Context, summary: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Meeting Summary")
            putExtra(Intent.EXTRA_TEXT, summary)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, "Export as PDF")
        )
    }

    /**
     * Shares the meeting summary as a Markdown text share intent.
     *
     * Uses [Intent.ACTION_SEND] with MIME type "text/plain" to avoid requiring
     * WRITE_EXTERNAL_STORAGE permission.
     *
     * @param context Activity context required to start the intent.
     * @param summary The summary text to share.
     */
    fun exportAsMarkdown(context: Context, summary: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Meeting Summary.md")
            putExtra(Intent.EXTRA_TEXT, summary)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, "Export as Markdown")
        )
    }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Parses action items from the summary text.
     *
     * Matches lines of the form: `- [Assignee]: description`
     * e.g. `- [Alice]: Review the design document by Friday`
     */
    internal fun parseActionItems(summaryText: String): List<ActionItem> {
        val pattern = Regex("""^- \[([^\]]+)\]: (.+)$""", RegexOption.MULTILINE)
        return pattern.findAll(summaryText).map { match ->
            ActionItem(
                assignee = match.groupValues[1].trim(),
                description = match.groupValues[2].trim()
            )
        }.toList()
    }

    /**
     * Extracts the transcript portion from the summary text.
     *
     * If the summary contains a "## Transcript" section, returns that section's content.
     * Otherwise, returns the full summary text as the transcript.
     */
    private fun extractTranscript(summaryText: String): String {
        val transcriptMarker = Regex("""## Transcript\s*\n""", RegexOption.IGNORE_CASE)
        val match = transcriptMarker.find(summaryText)
        return if (match != null) {
            // Return everything from the transcript marker onward
            summaryText.substring(match.range.last + 1).trim()
        } else {
            summaryText
        }
    }
}

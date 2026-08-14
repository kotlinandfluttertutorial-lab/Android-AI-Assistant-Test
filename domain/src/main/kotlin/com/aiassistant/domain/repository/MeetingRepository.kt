/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : MeetingRepository.kt
 * Purpose    : Domain contract defining data access operations for Meeting entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * File       : MeetingRepository.kt
 * Purpose    : Domain contract defining data access operations for Meeting entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * MeetingRepository.kt
 *
 * Purpose: Domain-layer repository interface for the Meeting Assistant feature.
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult)
 *
 * Requirements: 19.1
 *
 * Design decisions:
 * - Recording start/stop are decoupled: start returns a session ID used to correlate
 *   subsequent stop and summary calls.
 * - The MeetingSummary returned from getMeetingSummary is a plain String (AI-generated text)
 *   sufficient for the use case layer; richer parsing (action items, timestamps) is the
 *   responsibility of the ViewModel/UI layer.
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult

/**
 * Contract for Meeting Assistant operations between the domain and data layers.
 *
 * The data layer implementation coordinates with the Android MediaRecorder (for audio
 * capture) and the backend Transcription_Service (for transcript + AI summary).
 */
interface MeetingRepository {

    /**
     * Signals the backend that a new meeting recording session has started.
     *
     * The data layer initialises audio capture via MediaRecorder and opens a session
     * with the Transcription_Service.
     *
     * @param userId The identifier of the user starting the recording.
     * @return [ApiResult.Success] with the session identifier on success. The session ID
     *         must be supplied to [stopMeetingRecording] and [getMeetingSummary].
     */
    suspend fun startMeetingRecording(userId: String): ApiResult<String>

    /**
     * Stops the current meeting recording session and submits captured audio for
     * transcription.
     *
     * @param sessionId The session identifier returned by [startMeetingRecording].
     * @return [ApiResult.Success] with [Unit] when audio has been submitted successfully.
     */
    suspend fun stopMeetingRecording(sessionId: String): ApiResult<Unit>

    /**
     * Retrieves the AI-generated meeting summary for the given session, including
     * the timestamped transcript and extracted action items.
     *
     * The backend assembles the full summary text only after transcription is complete.
     *
     * @param sessionId The session identifier returned by [startMeetingRecording].
     * @return [ApiResult.Success] with the meeting summary text on success.
     */
    suspend fun getMeetingSummary(sessionId: String): ApiResult<String>
}

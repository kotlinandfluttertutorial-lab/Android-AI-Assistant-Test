/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GetMeetingSummaryUseCase.kt
 * Purpose    : Encapsulates the 'GetMeetingSummary' business operation
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
 * File       : GetMeetingSummaryUseCase.kt
 * Purpose    : Encapsulates the 'GetMeetingSummary' business operation
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
 * GetMeetingSummaryUseCase.kt
 *
 * Purpose: Retrieves the AI-generated meeting summary (transcript + action items) for
 *          a completed recording session.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), MeetingRepository
 *
 * Requirements: 19.1
 */

package com.aiassistant.domain.usecase.meeting

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.MeetingRepository
import javax.inject.Inject

/**
 * Use case for fetching the AI-generated summary of a meeting recording session.
 *
 * After transcription is complete, the `MeetingViewModel` transitions from `Processing`
 * to `Complete` and displays the summary and extracted action items. Export as PDF or
 * Markdown is available after this use case returns successfully (Requirement 19.1).
 *
 * @param meetingRepository Repository providing the meeting summary retrieval operation.
 */
class GetMeetingSummaryUseCase @Inject constructor(private val meetingRepository: MeetingRepository) {

    /**
     * Retrieves the meeting summary for the given session.
     *
     * @param sessionId The session identifier returned by [StartMeetingRecordingUseCase].
     * @return [ApiResult.Success] with the meeting summary text (including timestamped
     *         transcript and action items) on success.
     */
    suspend operator fun invoke(sessionId: String): ApiResult<String> = meetingRepository.getMeetingSummary(sessionId)
}

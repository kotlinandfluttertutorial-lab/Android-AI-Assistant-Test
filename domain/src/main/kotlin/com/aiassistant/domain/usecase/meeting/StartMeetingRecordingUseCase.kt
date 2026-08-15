/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : StartMeetingRecordingUseCase.kt
 * Purpose    : Encapsulates the 'StartMeetingRecording' business operation
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
 * StartMeetingRecordingUseCase.kt
 *
 * Purpose: Initiates a meeting recording session and returns a session identifier.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), MeetingRepository
 *
 * Requirements: 19.1
 */

package com.aiassistant.domain.usecase.meeting

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.MeetingRepository
import javax.inject.Inject

/**
 * Use case for starting a meeting recording session.
 *
 * The `MeetingViewModel` transitions from `Idle` to `Recording` state after a successful
 * invocation. The returned session ID must be passed to [StopMeetingRecordingUseCase] and
 * [GetMeetingSummaryUseCase] (Requirement 19.1).
 *
 * @param meetingRepository Repository providing the meeting recording start operation.
 */
class StartMeetingRecordingUseCase @Inject constructor(private val meetingRepository: MeetingRepository) {

    /**
     * Starts a meeting recording session for the given user.
     *
     * @param userId The identifier of the authenticated user starting the recording.
     *               Must not be blank.
     * @return [ApiResult.Success] with the session identifier (String) on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if [userId] is blank.
     */
    suspend operator fun invoke(userId: String): ApiResult<String> {
        if (userId.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "User ID must not be blank.",
                    fields = mapOf(FIELD_USER_ID to "A valid user ID is required.")
                )
            )
        }

        return meetingRepository.startMeetingRecording(userId)
    }

    internal companion object {
        const val FIELD_USER_ID = "userId"
    }
}

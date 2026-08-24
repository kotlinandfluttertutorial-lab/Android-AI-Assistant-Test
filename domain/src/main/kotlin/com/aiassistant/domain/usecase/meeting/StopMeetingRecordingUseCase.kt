/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : StopMeetingRecordingUseCase.kt
 * Purpose    : Encapsulates the 'StopMeetingRecording' business operation
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
 * StopMeetingRecordingUseCase.kt
 *
 * Purpose: Stops the active meeting recording session and submits captured audio for
 *          transcription.
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
 * Use case for stopping a meeting recording session.
 *
 * After a successful invocation the `MeetingViewModel` transitions from `Recording` to
 * `Processing` state while transcription is completed (Requirement 19.1).
 *
 * @param meetingRepository Repository providing the meeting recording stop operation.
 */
class StopMeetingRecordingUseCase @Inject constructor(private val meetingRepository: MeetingRepository) {

    /**
     * Stops the meeting recording and uploads the audio file for transcription.
     *
     * @param sessionId     The session identifier returned by [StartMeetingRecordingUseCase].
     * @param audioFilePath Absolute path to the recorded audio file on the device.
     * @return [ApiResult.Success] with [Unit] when audio has been submitted successfully.
     */
    suspend operator fun invoke(sessionId: String, audioFilePath: String): ApiResult<Unit> =
        meetingRepository.stopMeetingRecording(sessionId, audioFilePath)
}

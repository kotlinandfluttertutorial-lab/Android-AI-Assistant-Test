/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MeetingRepositoryImpl.kt
 * Purpose    : Production implementation of MeetingRepository. Coordinates the backend
 *              Transcription_Service session lifecycle via MeetingRemoteDataSource.
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation
 *
 * Dependencies: MeetingRemoteDataSource, ConnectivityObserver (core-network)
 * Requirements: 19.1, 5.6
 * ============================================================
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.meeting.MeetingRemoteDataSource
import com.aiassistant.domain.repository.MeetingRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [MeetingRepository].
 *
 * Delegates session management (start, stop, fetch summary) to the online
 * Transcription_Service via [MeetingRemoteDataSource], or returns
 * [ApiResult.NetworkUnavailable] when the device is offline.
 *
 * Audio capture hardware (MediaRecorder) lives entirely in the feature layer
 * ([com.aiassistant.feature.meeting.MeetingRecorderManager]) and is not a concern
 * of this repository. The repository manages only the backend session lifecycle.
 *
 * @param remoteDataSource     Retrofit-backed data source for the meeting endpoints.
 * @param connectivityObserver Synchronous connectivity state snapshot.
 */
@Singleton
class MeetingRepositoryImpl @Inject constructor(
    private val remoteDataSource: MeetingRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver
) : MeetingRepository {

    /**
     * Opens a meeting session on the Transcription_Service (Requirement 19.1).
     *
     * - **Online**: delegates to `POST /meeting/start` → returns session ID.
     * - **Offline**: returns [ApiResult.NetworkUnavailable] — meeting recording
     *   requires network access for session management and transcription.
     *
     * @param userId The identifier of the user starting the recording.
     * @return [ApiResult.Success] with the session identifier on success.
     */
    override suspend fun startMeetingRecording(userId: String): ApiResult<String> =
        if (connectivityObserver.isConnected()) {
            remoteDataSource.startMeeting(userId)
        } else {
            ApiResult.NetworkUnavailable
        }

    /**
     * Signals the Transcription_Service to stop recording and queue audio for
     * transcription (Requirement 19.1).
     *
     * - **Online**: delegates to `POST /meeting/{sessionId}/stop`.
     * - **Offline**: returns [ApiResult.NetworkUnavailable].
     *
     * @param sessionId The session identifier returned by [startMeetingRecording].
     * @return [ApiResult.Success] with [Unit] when audio is accepted into the queue.
     */
    override suspend fun stopMeetingRecording(sessionId: String): ApiResult<Unit> =
        if (connectivityObserver.isConnected()) {
            remoteDataSource.stopMeeting(sessionId)
        } else {
            ApiResult.NetworkUnavailable
        }

    /**
     * Retrieves the AI-generated meeting summary (Requirement 19.1).
     *
     * - **Online**: delegates to `GET /meeting/{sessionId}/summary`.
     * - **Offline**: returns [ApiResult.NetworkUnavailable].
     *
     * @param sessionId The session identifier returned by [startMeetingRecording].
     * @return [ApiResult.Success] with the full Markdown summary text on success.
     */
    override suspend fun getMeetingSummary(sessionId: String): ApiResult<String> =
        if (connectivityObserver.isConnected()) {
            remoteDataSource.getMeetingSummary(sessionId)
        } else {
            ApiResult.NetworkUnavailable
        }
}

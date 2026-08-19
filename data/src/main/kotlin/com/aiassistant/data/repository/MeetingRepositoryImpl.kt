/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MeetingRepositoryImpl.kt
 * Purpose    : Production implementation of MeetingRepository.
 *              Coordinates local audio recording (via file path) with the
 *              backend POST /transcription endpoint.
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
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.meeting.MeetingRemoteDataSource
import com.aiassistant.domain.repository.MeetingRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [MeetingRepository].
 *
 * The backend meeting flow uses a single multipart upload:
 * 1. [startMeetingRecording] — generates a local session ID; no network call.
 *    The feature layer (`MeetingRecorderManager`) starts hardware recording.
 * 2. [stopMeetingRecording] — uploads the recorded audio file to
 *    `POST /transcription` and caches the formatted summary.
 * 3. [getMeetingSummary] — returns the cached summary from step 2.
 *
 * The session ID is used as the key for the in-process summary cache. The cache
 * is cleared when the ViewModel calls [getMeetingSummary], keeping memory usage
 * bounded to a single session at a time.
 *
 * @param remoteDataSource     Retrofit-backed data source for the transcription endpoint.
 * @param connectivityObserver Synchronous connectivity state snapshot.
 */
@Singleton
class MeetingRepositoryImpl @Inject constructor(
    private val remoteDataSource: MeetingRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver
) : MeetingRepository {

    /**
     * In-process cache of session ID → (audioFilePath, summary).
     *
     * The audio file path is stored when the session starts so [stopMeetingRecording]
     * can locate the file. The summary is stored after transcription completes so
     * [getMeetingSummary] can return it without re-uploading.
     */
    private data class SessionData(
        val audioFilePath: String = "",
        val summary: String = ""
    )

    private val sessions = ConcurrentHashMap<String, SessionData>()

    /**
     * Opens a local meeting session and returns a session identifier.
     *
     * No network call is made here — the session is tracked in-process.
     * The feature layer (`MeetingRecorderManager`) handles hardware recording.
     *
     * @param userId The authenticated user's identifier (unused locally; kept for
     *               API compatibility with the domain interface).
     * @return [ApiResult.Success] with a new UUID session identifier.
     */
    override suspend fun startMeetingRecording(userId: String): ApiResult<String> {
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = SessionData()
        return ApiResult.Success(sessionId)
    }

    /**
     * Uploads the recorded audio file to `POST /transcription` and caches the result.
     *
     * The audio file path must have been registered via [registerAudioFile] before this
     * is called. If no file path is registered for [sessionId], returns an error.
     *
     * - **Online**: uploads to backend and caches the summary.
     * - **Offline**: returns [ApiResult.NetworkUnavailable].
     *
     * @param sessionId The session identifier returned by [startMeetingRecording].
     * @return [ApiResult.Success] with [Unit] when audio is uploaded successfully.
     */
    override suspend fun stopMeetingRecording(sessionId: String): ApiResult<Unit> {
        if (!connectivityObserver.isConnected()) {
            return ApiResult.NetworkUnavailable
        }

        val session = sessions[sessionId]
            ?: return ApiResult.Error(
                DomainError.ServerError(
                    message = "Meeting session '$sessionId' not found.",
                    httpStatusCode = 404
                )
            )

        if (session.audioFilePath.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "No audio file registered for session '$sessionId'. " +
                        "Call registerAudioFile() before stopMeetingRecording().",
                    fields = mapOf("audioFilePath" to "Audio file path must be set.")
                )
            )
        }

        val audioFile = File(session.audioFilePath)
        if (!audioFile.exists()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Audio file not found at '${session.audioFilePath}'.",
                    fields = mapOf("audioFilePath" to "File does not exist.")
                )
            )
        }

        return when (val result = remoteDataSource.transcribeAudio(audioFile)) {
            is ApiResult.Success -> {
                sessions[sessionId] = session.copy(summary = result.data)
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> result
            else -> ApiResult.NetworkUnavailable
        }
    }

    /**
     * Returns the cached meeting summary for [sessionId].
     *
     * The summary is populated by [stopMeetingRecording] after transcription completes.
     * Calling this before [stopMeetingRecording] returns an error.
     *
     * The session is removed from the cache after retrieval to free memory.
     *
     * @param sessionId The session identifier returned by [startMeetingRecording].
     * @return [ApiResult.Success] with the formatted Markdown summary text.
     */
    override suspend fun getMeetingSummary(sessionId: String): ApiResult<String> {
        val session = sessions.remove(sessionId)
            ?: return ApiResult.Error(
                DomainError.ServerError(
                    message = "Meeting session '$sessionId' not found or already retrieved.",
                    httpStatusCode = 404
                )
            )

        if (session.summary.isBlank()) {
            return ApiResult.Error(
                DomainError.ServerError(
                    message = "Meeting summary not yet available. " +
                        "Ensure stopMeetingRecording() completed successfully.",
                    httpStatusCode = 424 // Failed Dependency
                )
            )
        }

        return ApiResult.Success(session.summary)
    }

    /**
     * Registers the path of the recorded audio file for a session.
     *
     * Must be called by the feature layer (e.g., from `MeetingRecorderScreen`'s
     * `DisposableEffect` after `MeetingRecorderManager.stopRecording()`) before
     * calling [stopMeetingRecording].
     *
     * @param sessionId     The session identifier returned by [startMeetingRecording].
     * @param audioFilePath Absolute path to the `.m4a` file from `MeetingRecorderManager`.
     */
    fun registerAudioFile(sessionId: String, audioFilePath: String) {
        sessions.computeIfPresent(sessionId) { _, existing ->
            existing.copy(audioFilePath = audioFilePath)
        }
    }
}

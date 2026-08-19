/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MeetingApiService.kt
 * Purpose    : Retrofit service interface for the /transcription endpoint used by
 *              the Meeting Assistant feature.
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
 *
 * Dependencies: Retrofit, kotlinx.serialization
 * Requirements: 19.1, 5.6
 * ============================================================
 */
package com.aiassistant.data.remote.meeting

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

// ─── Response DTO ─────────────────────────────────────────────────────────────

/**
 * A single timestamped speaker-attributed transcript segment.
 *
 * @param timestamp Human-readable timestamp string (e.g. "00:00:00").
 * @param speaker   Speaker label (e.g. "Speaker 1").
 * @param text      Transcribed text for this segment.
 */
@Serializable
data class TranscriptSegmentDto(
    @SerialName("timestamp") val timestamp: String,
    @SerialName("speaker") val speaker: String,
    @SerialName("text") val text: String
)

/**
 * Response from `POST /transcription`.
 *
 * @param transcript      List of timestamped transcript segments.
 * @param language        Language code of the transcription.
 * @param durationSeconds Estimated audio duration in seconds.
 */
@Serializable
data class TranscriptionResponse(
    @SerialName("transcript") val transcript: List<TranscriptSegmentDto> = emptyList(),
    @SerialName("language") val language: String = "en",
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0
)

// ─── Retrofit service ─────────────────────────────────────────────────────────

/**
 * Retrofit service for the meeting transcription endpoint.
 *
 * The backend exposes a single multipart upload endpoint at `POST /transcription`
 * which accepts an audio file and returns a timestamped speaker-attributed transcript.
 *
 * The Android meeting flow:
 * 1. `MeetingRecorderManager` captures audio to a local `.m4a` file.
 * 2. After the user stops recording, `MeetingRepositoryImpl.stopMeetingRecording()`
 *    uploads the file to this endpoint.
 * 3. The response transcript is formatted into the meeting summary by `MeetingViewModel`.
 *
 * Consumed exclusively by [MeetingRemoteDataSource].
 *
 * Requirements: 19.1, 5.6
 */
interface MeetingApiService {

    /**
     * Uploads an audio file for transcription (Requirement 19.1).
     *
     * @param audioFile  Multipart audio file part (mp3, wav, m4a, ogg, webm; max 100 MB).
     * @param language   Optional language code form field (default "en").
     * @return [TranscriptionResponse] containing the timestamped transcript segments.
     */
    @Multipart
    @POST("transcription")
    suspend fun transcribeAudio(
        @Part audioFile: MultipartBody.Part,
        @Part("language") language: RequestBody
    ): TranscriptionResponse
}

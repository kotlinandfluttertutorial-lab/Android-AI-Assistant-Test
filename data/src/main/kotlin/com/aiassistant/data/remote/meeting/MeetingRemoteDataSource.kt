/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MeetingRemoteDataSource.kt
 * Purpose    : Wraps MeetingApiService Retrofit calls and assembles the
 *              meeting summary from the transcript response.
 *
 * Architecture Layer : Data
 * Pattern Used       : Data Source (remote)
 *
 * Dependencies: MeetingApiService, ApiResult, DomainError, DispatcherProvider
 * Requirements: 19.1, 5.6
 * ============================================================
 */
package com.aiassistant.data.remote.meeting

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * Remote data source for meeting transcription network operations.
 *
 * Uploads the locally recorded audio file to `POST /transcription` and
 * formats the returned transcript segments into a meeting summary string
 * suitable for display in `MeetingSummaryScreen`.
 *
 * @param api         Retrofit service for the transcription endpoint.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class MeetingRemoteDataSource @Inject constructor(
    private val api: MeetingApiService,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Uploads [audioFile] for transcription and returns the formatted summary.
     *
     * The summary is a Markdown-formatted string built from the transcript segments,
     * with each segment on its own line formatted as `[timestamp] Speaker: text`.
     * This format is compatible with the action-item regex parser in `MeetingViewModel`.
     *
     * @param audioFile  The recorded `.m4a` audio file from `MeetingRecorderManager`.
     * @param language   BCP 47 language code (default "en").
     * @return [ApiResult.Success] with the formatted meeting summary on success.
     */
    suspend fun transcribeAudio(audioFile: File, language: String = "en"): ApiResult<String> =
        withContext(dispatchers.io) {
            safeApiCall {
                val requestBody = audioFile.asRequestBody("audio/mp4".toMediaType())
                val filePart = MultipartBody.Part.createFormData(
                    name = "audio_file",
                    filename = audioFile.name,
                    body = requestBody
                )
                val languagePart = language.toRequestBody("text/plain".toMediaType())

                val response = api.transcribeAudio(filePart, languagePart)
                formatSummary(response)
            }
        }

    // ─── Formatting helper ────────────────────────────────────────────────────

    /**
     * Converts the [TranscriptionResponse] into a Markdown meeting summary string.
     *
     * Format:
     * ```
     * ## Transcript
     *
     * [00:00:00] Speaker 1: Welcome to the meeting...
     * [00:00:30] Speaker 2: Thank you for joining...
     * ```
     *
     * Duration is appended as a metadata line at the end.
     */
    private fun formatSummary(response: TranscriptionResponse): String {
        val sb = StringBuilder()
        sb.appendLine("## Transcript")
        sb.appendLine()

        if (response.transcript.isEmpty()) {
            sb.appendLine("No transcript segments were generated.")
        } else {
            response.transcript.forEach { segment ->
                sb.appendLine("[${segment.timestamp}] ${segment.speaker}: ${segment.text}")
            }
        }

        sb.appendLine()
        val durationMin = (response.durationSeconds / 60).toInt()
        val durationSec = (response.durationSeconds % 60).toInt()
        sb.append("_Duration: ${durationMin}m ${durationSec}s · Language: ${response.language}_")

        return sb.toString()
    }

    // ─── Safe call helper ─────────────────────────────────────────────────────

    private suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        ApiResult.Error(e.toDomainError())
    } catch (e: IOException) {
        ApiResult.Error(
            DomainError.NetworkError(
                message = e.message ?: "A network I/O error occurred.",
                cause = e
            )
        )
    }

    private fun HttpException.toDomainError(): DomainError = when (code()) {
        401 -> DomainError.Unauthorized(cause = this)
        403 -> DomainError.Forbidden(cause = this)
        422 -> DomainError.ValidationError(
            message = "Invalid audio file (HTTP 422). " +
                "Supported formats: mp3, wav, m4a, ogg, webm. Max 100 MB.",
            cause = this
        )
        in 400..499 -> DomainError.ValidationError(
            message = "Invalid request (HTTP ${code()}).",
            cause = this
        )
        in 500..599 -> DomainError.ServerError(
            message = "Transcription service error (HTTP ${code()}).",
            httpStatusCode = code(),
            cause = this
        )
        else -> DomainError.NetworkError(
            message = "Unexpected HTTP response: ${code()}.",
            cause = this
        )
    }
}

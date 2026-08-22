/**
 * MeetingRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [MeetingRepositoryImpl], covering the new transcription flow:
 *   - startMeetingRecording: always succeeds, returns a UUID session ID, no network call.
 *   - stopMeetingRecording: uploads audio file via MeetingRemoteDataSource.
 *   - getMeetingSummary: returns the cached summary from stopMeetingRecording.
 *   - Offline: stopMeetingRecording returns NetworkUnavailable immediately.
 *
 * Architecture: data module — pure JVM unit tests, no Android framework dependencies.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mock ConnectivityObserver and MeetingRemoteDataSource
 * - kotlinx.coroutines.test — runTest
 * - java.io.File / TemporaryFolder — creates real temp files to satisfy File.exists() checks
 *
 * Requirements covered: 19.1, 5.6
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.meeting.MeetingRemoteDataSource
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.File
import kotlinx.coroutines.test.runTest

class MeetingRepositoryImplTest :
    DescribeSpec({

        val remoteDataSource = mockk<MeetingRemoteDataSource>()
        val connectivityObserver = mockk<ConnectivityObserver>()

        afterEach {
            unmockkAll()
        }

        val summaryText = "## Transcript\n\n[00:00:00] Speaker 1: Let's get started.\n"

        // ─── startMeetingRecording ────────────────────────────────────────────

        describe("startMeetingRecording()") {

            it("always succeeds and returns a non-blank session ID") {
                runTest {
                    val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)
                    val result = repository.startMeetingRecording(userId = "user-123")

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success).data.shouldNotBeBlank()
                }
            }

            it("returns a different session ID for each call") {
                runTest {
                    val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)
                    val r1 = repository.startMeetingRecording("user-a") as ApiResult.Success
                    val r2 = repository.startMeetingRecording("user-b") as ApiResult.Success

                    r1.data shouldNotBe r2.data
                }
            }

            it("does not call remoteDataSource") {
                runTest {
                    val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)
                    repository.startMeetingRecording("user-xyz")

                    coVerify(exactly = 0) { remoteDataSource.transcribeAudio(any(), any()) }
                }
            }
        }

        // ─── stopMeetingRecording ─────────────────────────────────────────────

        describe("stopMeetingRecording()") {

            it("uploads the audio file and returns Success") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)

                    val sessionResult = repository.startMeetingRecording("user-1")
                    val sessionId = (sessionResult as ApiResult.Success).data

                    // Create a real temp file so File.exists() returns true
                    val audioFile = File.createTempFile("meeting_test", ".m4a")
                    try {
                        coEvery { remoteDataSource.transcribeAudio(audioFile) } returns
                            ApiResult.Success(summaryText)

                        val result = repository.stopMeetingRecording(sessionId, audioFile.absolutePath)

                        result.shouldBeInstanceOf<ApiResult.Success<Unit>>()
                        coVerify(exactly = 1) { remoteDataSource.transcribeAudio(audioFile) }
                    } finally {
                        audioFile.delete()
                    }
                }
            }

            it("returns NetworkUnavailable when offline without calling remoteDataSource") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false
                    val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)

                    val sessionId = (repository.startMeetingRecording("user-1") as ApiResult.Success).data

                    val result = repository.stopMeetingRecording(sessionId, "/some/path.m4a")

                    result shouldBe ApiResult.NetworkUnavailable
                    coVerify(exactly = 0) { remoteDataSource.transcribeAudio(any(), any()) }
                }
            }

            it("returns 404 error for unknown session ID") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)

                    val result = repository.stopMeetingRecording("unknown-session", "/path.m4a")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ServerError>()
                    (error as DomainError.ServerError).httpStatusCode shouldBe 404
                }
            }

            it("returns ValidationError when audio file does not exist") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)

                    val sessionId = (repository.startMeetingRecording("user-1") as ApiResult.Success).data

                    val result = repository.stopMeetingRecording(sessionId, "/nonexistent/audio.m4a")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }

            it("propagates Error from remoteDataSource") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)
                    val sessionId = (repository.startMeetingRecording("user-1") as ApiResult.Success).data

                    val audioFile = File.createTempFile("meeting_err", ".m4a")
                    try {
                        val error = DomainError.ServerError("Transcription failed.", 500)
                        coEvery { remoteDataSource.transcribeAudio(audioFile) } returns ApiResult.Error(error)

                        val result = repository.stopMeetingRecording(sessionId, audioFile.absolutePath)

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error shouldBe error
                    } finally {
                        audioFile.delete()
                    }
                }
            }
        }

        // ─── getMeetingSummary ────────────────────────────────────────────────

        describe("getMeetingSummary()") {

            it("returns the summary cached by stopMeetingRecording") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)
                    val sessionId = (repository.startMeetingRecording("user-1") as ApiResult.Success).data

                    val audioFile = File.createTempFile("meeting_sum", ".m4a")
                    try {
                        coEvery { remoteDataSource.transcribeAudio(audioFile) } returns
                            ApiResult.Success(summaryText)

                        repository.stopMeetingRecording(sessionId, audioFile.absolutePath)

                        val result = repository.getMeetingSummary(sessionId)

                        result.shouldBeInstanceOf<ApiResult.Success<String>>()
                        (result as ApiResult.Success).data shouldBe summaryText
                    } finally {
                        audioFile.delete()
                    }
                }
            }

            it("returns 404 error when session is not found or already retrieved") {
                runTest {
                    val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)

                    val result = repository.getMeetingSummary("nonexistent-session")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ServerError>()
                    (error as DomainError.ServerError).httpStatusCode shouldBe 404
                }
            }

            it("removes session from cache after retrieval (prevents double-fetch)") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)
                    val sessionId = (repository.startMeetingRecording("user-1") as ApiResult.Success).data

                    val audioFile = File.createTempFile("meeting_once", ".m4a")
                    try {
                        coEvery { remoteDataSource.transcribeAudio(audioFile) } returns
                            ApiResult.Success(summaryText)
                        repository.stopMeetingRecording(sessionId, audioFile.absolutePath)

                        // First fetch succeeds
                        repository.getMeetingSummary(sessionId)
                            .shouldBeInstanceOf<ApiResult.Success<String>>()

                        // Second fetch returns 404 (session removed)
                        val secondResult = repository.getMeetingSummary(sessionId)
                        secondResult.shouldBeInstanceOf<ApiResult.Error>()
                    } finally {
                        audioFile.delete()
                    }
                }
            }
        }
    })

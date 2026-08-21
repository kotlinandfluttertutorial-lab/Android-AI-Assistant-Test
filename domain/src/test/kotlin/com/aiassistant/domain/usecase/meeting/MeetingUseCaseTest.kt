/**
 * MeetingUseCaseTest.kt — domain module unit tests
 *
 * Tests for meeting use cases:
 *   - [StartMeetingRecordingUseCase] — validates userId not blank; delegates to repository
 *   - [StopMeetingRecordingUseCase]  — pure delegation; no validation
 *   - [GetMeetingSummaryUseCase]     — pure delegation; no validation
 *
 * Requirements: 21.1
 * Related requirements: 19.1
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK for MeetingRepository mocking
 */

package com.aiassistant.domain.usecase.meeting

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.MeetingRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private const val VALID_USER_ID = "user-456"
private const val SAMPLE_SESSION_ID = "session-abc-123"
private const val SAMPLE_SUMMARY = "Meeting summary with action items:\n1. Follow up with team\n2. Update docs"

// ─── StartMeetingRecordingUseCase ─────────────────────────────────────────────

class StartMeetingRecordingUseCaseTest :
    DescribeSpec({

        val meetingRepository = mockk<MeetingRepository>()
        val startMeetingRecordingUseCase = StartMeetingRecordingUseCase(meetingRepository)

        beforeEach {
            clearMocks(meetingRepository)
        }

        describe("StartMeetingRecordingUseCase") {

            describe("successful start") {

                it("returns Success with session ID when userId is valid") {
                    coEvery {
                        meetingRepository.startMeetingRecording(VALID_USER_ID)
                    } returns ApiResult.Success(SAMPLE_SESSION_ID)

                    val result = startMeetingRecordingUseCase(VALID_USER_ID)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success<String>).data shouldBe SAMPLE_SESSION_ID
                }

                it("delegates to repository exactly once with the provided userId") {
                    coEvery {
                        meetingRepository.startMeetingRecording(VALID_USER_ID)
                    } returns ApiResult.Success(SAMPLE_SESSION_ID)

                    startMeetingRecordingUseCase(VALID_USER_ID)

                    coVerify(exactly = 1) { meetingRepository.startMeetingRecording(VALID_USER_ID) }
                }
            }

            describe("userId validation") {

                it("returns ValidationError when userId is blank") {
                    val result = startMeetingRecordingUseCase("")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when userId is only whitespace") {
                    val result = startMeetingRecordingUseCase("   ")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("ValidationError contains 'userId' in fields map") {
                    val result = startMeetingRecordingUseCase("")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey StartMeetingRecordingUseCase.FIELD_USER_ID
                }

                it("does NOT call repository when userId is blank") {
                    startMeetingRecordingUseCase("")

                    coVerify(exactly = 0) { meetingRepository.startMeetingRecording(any()) }
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery {
                        meetingRepository.startMeetingRecording(any())
                    } returns ApiResult.NetworkUnavailable

                    val result = startMeetingRecordingUseCase(VALID_USER_ID)

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery {
                        meetingRepository.startMeetingRecording(any())
                    } returns ApiResult.Error(error)

                    val result = startMeetingRecordingUseCase(VALID_USER_ID)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── StopMeetingRecordingUseCase ──────────────────────────────────────────────

class StopMeetingRecordingUseCaseTest :
    DescribeSpec({

        val meetingRepository = mockk<MeetingRepository>()
        val stopMeetingRecordingUseCase = StopMeetingRecordingUseCase(meetingRepository)

        beforeEach {
            clearMocks(meetingRepository)
        }

        describe("StopMeetingRecordingUseCase") {

            describe("successful stop") {

                it("returns Success with Unit when repository succeeds") {
                    coEvery {
                        meetingRepository.stopMeetingRecording(SAMPLE_SESSION_ID, any())
                    } returns ApiResult.Success(Unit)

                    val result = stopMeetingRecordingUseCase(SAMPLE_SESSION_ID, "")

                    result.shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }

                it("delegates to repository exactly once with the given sessionId") {
                    coEvery {
                        meetingRepository.stopMeetingRecording(SAMPLE_SESSION_ID, any())
                    } returns ApiResult.Success(Unit)

                    stopMeetingRecordingUseCase(SAMPLE_SESSION_ID, "")

                    coVerify(exactly = 1) { meetingRepository.stopMeetingRecording(SAMPLE_SESSION_ID, any()) }
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery {
                        meetingRepository.stopMeetingRecording(any(), any())
                    } returns ApiResult.NetworkUnavailable

                    val result = stopMeetingRecordingUseCase(SAMPLE_SESSION_ID, "")

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery {
                        meetingRepository.stopMeetingRecording(any(), any())
                    } returns ApiResult.Error(error)

                    val result = stopMeetingRecordingUseCase(SAMPLE_SESSION_ID, "")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── GetMeetingSummaryUseCase ─────────────────────────────────────────────────

class GetMeetingSummaryUseCaseTest :
    DescribeSpec({

        val meetingRepository = mockk<MeetingRepository>()
        val getMeetingSummaryUseCase = GetMeetingSummaryUseCase(meetingRepository)

        beforeEach {
            clearMocks(meetingRepository)
        }

        describe("GetMeetingSummaryUseCase") {

            describe("successful retrieval") {

                it("returns Success with meeting summary text when repository succeeds") {
                    coEvery {
                        meetingRepository.getMeetingSummary(SAMPLE_SESSION_ID)
                    } returns ApiResult.Success(SAMPLE_SUMMARY)

                    val result = getMeetingSummaryUseCase(SAMPLE_SESSION_ID)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success<String>).data shouldBe SAMPLE_SUMMARY
                }

                it("delegates to repository exactly once with the given sessionId") {
                    coEvery {
                        meetingRepository.getMeetingSummary(SAMPLE_SESSION_ID)
                    } returns ApiResult.Success(SAMPLE_SUMMARY)

                    getMeetingSummaryUseCase(SAMPLE_SESSION_ID)

                    coVerify(exactly = 1) { meetingRepository.getMeetingSummary(SAMPLE_SESSION_ID) }
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery {
                        meetingRepository.getMeetingSummary(any())
                    } returns ApiResult.NetworkUnavailable

                    val result = getMeetingSummaryUseCase(SAMPLE_SESSION_ID)

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery {
                        meetingRepository.getMeetingSummary(any())
                    } returns ApiResult.Error(error)

                    val result = getMeetingSummaryUseCase(SAMPLE_SESSION_ID)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

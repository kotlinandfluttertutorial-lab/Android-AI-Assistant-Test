/**
 * MeetingRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [MeetingRepositoryImpl], which is a stub that always returns
 *          HTTP 501 (backend not yet wired). Verifies stub behavior for all three methods.
 * Architecture: data module — pure JVM unit tests, no Android framework dependencies.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 19.1, 5.6
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class MeetingRepositoryImplTest :
    DescribeSpec({

        val repository = MeetingRepositoryImpl()

        describe("startMeetingRecording()") {

            it("returns ServerError with 501 status") {
                runTest {
                    val result = repository.startMeetingRecording(userId = "user-123")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ServerError>()
                    (error as DomainError.ServerError).httpStatusCode shouldBe 501
                }
            }

            it("error message describes backend connection status") {
                runTest {
                    val result = repository.startMeetingRecording(userId = "user-abc")

                    val error = (result as ApiResult.Error).error as DomainError.ServerError
                    error.message shouldBe "Meeting recording backend not yet connected"
                }
            }

            it("returns error for any userId") {
                runTest {
                    listOf("user-1", "user-2", "", "admin").forEach { userId ->
                        val result = repository.startMeetingRecording(userId)
                        result.shouldBeInstanceOf<ApiResult.Error>()
                    }
                }
            }
        }

        describe("stopMeetingRecording()") {

            it("returns ServerError with 501 status") {
                runTest {
                    val result = repository.stopMeetingRecording(sessionId = "session-xyz")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ServerError>()
                    (error as DomainError.ServerError).httpStatusCode shouldBe 501
                }
            }

            it("error message describes backend connection status") {
                runTest {
                    val result = repository.stopMeetingRecording(sessionId = "session-abc")

                    val error = (result as ApiResult.Error).error as DomainError.ServerError
                    error.message shouldBe "Meeting recording backend not yet connected"
                }
            }

            it("returns error for any sessionId") {
                runTest {
                    listOf("session-1", "session-2", "").forEach { sessionId ->
                        val result = repository.stopMeetingRecording(sessionId)
                        result.shouldBeInstanceOf<ApiResult.Error>()
                    }
                }
            }
        }

        describe("getMeetingSummary()") {

            it("returns ServerError with 501 status") {
                runTest {
                    val result = repository.getMeetingSummary(sessionId = "session-xyz")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ServerError>()
                    (error as DomainError.ServerError).httpStatusCode shouldBe 501
                }
            }

            it("error message describes backend connection status") {
                runTest {
                    val result = repository.getMeetingSummary(sessionId = "session-123")

                    val error = (result as ApiResult.Error).error as DomainError.ServerError
                    error.message shouldBe "Meeting summary backend not yet connected"
                }
            }

            it("returns error for any sessionId") {
                runTest {
                    listOf("session-a", "session-b", "unknown").forEach { sessionId ->
                        val result = repository.getMeetingSummary(sessionId)
                        result.shouldBeInstanceOf<ApiResult.Error>()
                    }
                }
            }
        }
    })

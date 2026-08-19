/**
 * MeetingRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [MeetingRepositoryImpl], covering:
 *   - Online path: each method delegates to MeetingRemoteDataSource and returns its result.
 *   - Offline path: each method returns ApiResult.NetworkUnavailable without hitting
 *     the network.
 *
 * Architecture: data module — pure JVM unit tests, no Android framework dependencies.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mock ConnectivityObserver and MeetingRemoteDataSource
 * - kotlinx.coroutines.test — runTest
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
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class MeetingRepositoryImplTest :
    DescribeSpec({

        val remoteDataSource = mockk<MeetingRemoteDataSource>()
        val connectivityObserver = mockk<ConnectivityObserver>()
        val repository = MeetingRepositoryImpl(remoteDataSource, connectivityObserver)

        val userId = "user-123"
        val sessionId = "session-xyz"
        val summaryText = "## Summary\nThe team agreed on a Q3 deadline.\n- [Alice]: Review the spec by Friday"

        // ─── startMeetingRecording ────────────────────────────────────────────

        describe("startMeetingRecording() — online") {

            it("delegates to remoteDataSource and returns Success with session ID") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.startMeeting(userId) } returns ApiResult.Success(sessionId)

                    val result = repository.startMeetingRecording(userId)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success).data shouldBe sessionId
                    coVerify(exactly = 1) { remoteDataSource.startMeeting(userId) }
                }
            }

            it("propagates Error from remoteDataSource") {
                runTest {
                    val domainError = DomainError.ServerError(
                        message = "Server error (HTTP 500).",
                        httpStatusCode = 500
                    )
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.startMeeting(userId) } returns ApiResult.Error(domainError)

                    val result = repository.startMeetingRecording(userId)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe domainError
                }
            }
        }

        describe("startMeetingRecording() — offline") {

            it("returns NetworkUnavailable without calling remoteDataSource") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.startMeetingRecording(userId)

                    result shouldBe ApiResult.NetworkUnavailable
                    coVerify(exactly = 0) { remoteDataSource.startMeeting(any()) }
                }
            }
        }

        // ─── stopMeetingRecording ─────────────────────────────────────────────

        describe("stopMeetingRecording() — online") {

            it("delegates to remoteDataSource and returns Success") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.stopMeeting(sessionId) } returns ApiResult.Success(Unit)

                    val result = repository.stopMeetingRecording(sessionId)

                    result.shouldBeInstanceOf<ApiResult.Success<Unit>>()
                    coVerify(exactly = 1) { remoteDataSource.stopMeeting(sessionId) }
                }
            }

            it("propagates Error from remoteDataSource") {
                runTest {
                    val domainError = DomainError.ServerError(
                        message = "Meeting session not found (HTTP 404).",
                        httpStatusCode = 404
                    )
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.stopMeeting(sessionId) } returns ApiResult.Error(domainError)

                    val result = repository.stopMeetingRecording(sessionId)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe domainError
                }
            }
        }

        describe("stopMeetingRecording() — offline") {

            it("returns NetworkUnavailable without calling remoteDataSource") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.stopMeetingRecording(sessionId)

                    result shouldBe ApiResult.NetworkUnavailable
                    coVerify(exactly = 0) { remoteDataSource.stopMeeting(any()) }
                }
            }
        }

        // ─── getMeetingSummary ────────────────────────────────────────────────

        describe("getMeetingSummary() — online") {

            it("delegates to remoteDataSource and returns Success with summary text") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.getMeetingSummary(sessionId) } returns ApiResult.Success(summaryText)

                    val result = repository.getMeetingSummary(sessionId)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success).data shouldBe summaryText
                    coVerify(exactly = 1) { remoteDataSource.getMeetingSummary(sessionId) }
                }
            }

            it("propagates Error from remoteDataSource") {
                runTest {
                    val domainError = DomainError.ServerError(
                        message = "Server error (HTTP 500).",
                        httpStatusCode = 500
                    )
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.getMeetingSummary(sessionId) } returns ApiResult.Error(domainError)

                    val result = repository.getMeetingSummary(sessionId)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe domainError
                }
            }
        }

        describe("getMeetingSummary() — offline") {

            it("returns NetworkUnavailable without calling remoteDataSource") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.getMeetingSummary(sessionId)

                    result shouldBe ApiResult.NetworkUnavailable
                    coVerify(exactly = 0) { remoteDataSource.getMeetingSummary(any()) }
                }
            }
        }
    })

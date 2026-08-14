/**
 * MessageRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [MessageRepositoryImpl], covering:
 *   - sendMessage() — offline queuing, online success, remote error
 *   - regenerateMessage() — online success, offline guard
 *
 * Architecture: data module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking MessageLocalDataSource, MessageRemoteDataSource, ConnectivityObserver
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 2.6, 10.2, 10.3
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.local.MessageLocalDataSource
import com.aiassistant.data.remote.message.MessageDto
import com.aiassistant.data.remote.message.MessageRemoteDataSource
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

// ─── Fixtures ─────────────────────────────────────────────────────────────────

private fun fakeMessageDto(
    id: String = "msg-server-1",
    conversationId: String = "conv-1",
    role: String = "assistant",
    content: String = "AI response",
    inputTokens: Int = 20,
    outputTokens: Int = 60,
    provider: String = "openai",
    createdAt: Long = 2_000_000L
) = MessageDto(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    provider = provider,
    createdAt = createdAt
)

// ─── Spec ─────────────────────────────────────────────────────────────────────

class MessageRepositoryImplTest :
    DescribeSpec({

        val localSource: MessageLocalDataSource = mockk(relaxed = true)
        val remoteSource: MessageRemoteDataSource = mockk()
        val connectivityObserver: ConnectivityObserver = mockk()
        val dispatchers = TestDispatcherProvider()

        lateinit var repository: MessageRepositoryImpl

        beforeEach {
            clearAllMocks()
            every { connectivityObserver.isConnectedFlow } returns flowOf(true)
            repository = MessageRepositoryImpl(
                localSource = localSource,
                remoteSource = remoteSource,
                connectivityObserver = connectivityObserver,
                dispatchers = dispatchers
            )
        }

        // ─── sendMessage() ────────────────────────────────────────────────────────

        describe("sendMessage()") {

            describe("offline path") {
                it("persists user message locally with syncStatus=pending") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.sendMessage("conv-1", "Hello", "openai")

                        coVerify(exactly = 1) {
                            localSource.insertMessage(match { it.syncStatus == "pending" && it.content == "Hello" })
                        }
                    }
                }

                it("returns NetworkUnavailable without calling remote") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.sendMessage("conv-1", "Hello", "openai")

                        result shouldBe ApiResult.NetworkUnavailable
                        coVerify(exactly = 0) { remoteSource.sendMessage(any(), any(), any()) }
                    }
                }
            }

            describe("online success path") {
                it("inserts user message as pending, then inserts AI response as synced") {
                    runTest {
                        val serverDto = fakeMessageDto()
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteSource.sendMessage("conv-1", "Hello", "openai")
                        } returns ApiResult.Success(serverDto)

                        repository.sendMessage("conv-1", "Hello", "openai")

                        // User message inserted as pending first
                        coVerify(exactly = 1) {
                            localSource.insertMessage(match { it.syncStatus == "pending" })
                        }
                        // AI response inserted after remote success
                        coVerify(exactly = 1) {
                            localSource.insertMessage(match { it.role == "assistant" && it.syncStatus == "synced" })
                        }
                    }
                }

                it("updates user message syncStatus to synced after successful remote call") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteSource.sendMessage(any(), any(), any())
                        } returns ApiResult.Success(fakeMessageDto())

                        repository.sendMessage("conv-1", "Hello", "openai")

                        coVerify(exactly = 1) { localSource.updateSyncStatus(any(), "synced") }
                    }
                }

                it("returns ApiResult.Success with the AI response message") {
                    runTest {
                        val serverDto = fakeMessageDto(content = "AI says hello", outputTokens = 50)
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteSource.sendMessage(any(), any(), any())
                        } returns ApiResult.Success(serverDto)

                        val result = repository.sendMessage("conv-1", "Hello", "openai")

                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        val message = (result as ApiResult.Success).data
                        message.content shouldBe "AI says hello"
                        message.outputTokens shouldBe 50
                        message.syncStatus shouldBe "synced"
                    }
                }
            }

            describe("online failure path") {
                it("keeps user message as pending when remote call fails") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteSource.sendMessage(any(), any(), any())
                        } returns ApiResult.Error(DomainError.ServerError("Server error", 500))

                        val result = repository.sendMessage("conv-1", "Hello", "openai")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        // No AI message inserted
                        coVerify(exactly = 0) {
                            localSource.insertMessage(match { it.role == "assistant" })
                        }
                        // No status update to synced
                        coVerify(exactly = 0) { localSource.updateSyncStatus(any(), "synced") }
                    }
                }
            }
        }

        // ─── regenerateMessage() ──────────────────────────────────────────────────

        describe("regenerateMessage()") {

            describe("offline path") {
                it("returns NetworkUnavailable without calling remote") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.regenerateMessage("conv-1", "msg-orig-1")

                        result shouldBe ApiResult.NetworkUnavailable
                        coVerify(exactly = 0) {
                            remoteSource.regenerateMessage(any(), any())
                        }
                    }
                }
            }

            describe("online success path") {
                it("inserts the regenerated message locally and returns Success") {
                    runTest {
                        val regenDto = fakeMessageDto(id = "msg-regen-1", content = "Regenerated response")
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteSource.regenerateMessage("conv-1", "msg-orig-1")
                        } returns ApiResult.Success(regenDto)

                        val result = repository.regenerateMessage("conv-1", "msg-orig-1")

                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        (result as ApiResult.Success).data.content shouldBe "Regenerated response"
                        coVerify(exactly = 1) {
                            localSource.insertMessage(match { it.id == "msg-regen-1" })
                        }
                    }
                }
            }

            describe("online failure path") {
                it("propagates remote error without inserting anything locally") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteSource.regenerateMessage(any(), any())
                        } returns ApiResult.Error(DomainError.NetworkError("Timeout"))

                        val result = repository.regenerateMessage("conv-1", "msg-1")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        coVerify(exactly = 0) { localSource.insertMessage(any()) }
                    }
                }
            }
        }
    })

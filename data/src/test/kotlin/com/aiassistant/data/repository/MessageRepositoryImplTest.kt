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
import io.mockk.unmockkAll
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

        afterEach {
            unmockkAll()
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

        // ─── syncOfflineQueue() ───────────────────────────────────────────────────

        describe("syncOfflineQueue()") {

            describe("offline guard") {
                it("returns NetworkUnavailable without reading pending messages") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.syncOfflineQueue()

                        result shouldBe ApiResult.NetworkUnavailable
                        coVerify(exactly = 0) { localSource.getPendingMessages() }
                    }
                }
            }

            describe("empty queue") {
                it("returns Success(0) when no pending messages exist") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { localSource.getPendingMessages() } returns emptyList()

                        val result = repository.syncOfflineQueue()

                        result shouldBe ApiResult.Success(0)
                        coVerify(exactly = 0) { remoteSource.sendMessage(any(), any(), any()) }
                    }
                }
            }

            describe("successful sync") {
                it("syncs a single pending message and returns Success(1)") {
                    runTest {
                        val entity = com.aiassistant.core.database.entity.MessageEntity(
                            id = "msg-q1", conversationId = "conv-1", role = "user",
                            content = "Hello", inputTokens = 0, outputTokens = 0,
                            provider = "openai", syncStatus = "pending", createdAt = 1L
                        )
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { localSource.getPendingMessages() } returns listOf(entity)
                        coEvery {
                            remoteSource.sendMessage("conv-1", "Hello", "openai")
                        } returns ApiResult.Success(
                            fakeMessageDto(content = "Server content", inputTokens = 10, outputTokens = 30)
                        )

                        val result = repository.syncOfflineQueue()

                        result shouldBe ApiResult.Success(1)
                        // Server-wins: updateMessage with server content
                        coVerify(exactly = 1) {
                            localSource.updateMessage(
                                match {
                                    it.content == "Server content" &&
                                        it.syncStatus == "synced" &&
                                        it.inputTokens == 10 &&
                                        it.outputTokens == 30
                                }
                            )
                        }
                    }
                }

                it("syncs multiple pending messages and returns correct count") {
                    runTest {
                        fun makeEntity(id: String, content: String) =
                            com.aiassistant.core.database.entity.MessageEntity(
                                id = id, conversationId = "conv-1", role = "user",
                                content = content, inputTokens = 0, outputTokens = 0,
                                provider = "openai", syncStatus = "pending", createdAt = 1L
                            )
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { localSource.getPendingMessages() } returns listOf(
                            makeEntity("m1", "Msg 1"),
                            makeEntity("m2", "Msg 2"),
                            makeEntity("m3", "Msg 3")
                        )
                        coEvery { remoteSource.sendMessage(any(), any(), any()) } returns
                            ApiResult.Success(fakeMessageDto())

                        val result = repository.syncOfflineQueue()

                        result shouldBe ApiResult.Success(3)
                    }
                }
            }

            describe("failure and retry logic") {
                it("marks message as failed after MAX_RETRY_ATTEMPTS errors") {
                    runTest {
                        val entity = com.aiassistant.core.database.entity.MessageEntity(
                            id = "msg-fail", conversationId = "conv-1", role = "user",
                            content = "Will fail", inputTokens = 0, outputTokens = 0,
                            provider = "openai", syncStatus = "pending", createdAt = 1L
                        )
                        // Same message appears 3 times to simulate 3 failures
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { localSource.getPendingMessages() } returns listOf(entity, entity, entity)
                        coEvery { remoteSource.sendMessage(any(), any(), any()) } returns
                            ApiResult.Error(DomainError.ServerError("Server error", 500))

                        val result = repository.syncOfflineQueue()

                        result shouldBe ApiResult.Success(0)
                        // updateSyncStatus("failed") called once after the 3rd attempt
                        coVerify(exactly = 1) { localSource.updateSyncStatus("msg-fail", "failed") }
                    }
                }

                it("skips message on 4th occurrence after marking it failed") {
                    runTest {
                        val entity = com.aiassistant.core.database.entity.MessageEntity(
                            id = "msg-over", conversationId = "conv-1", role = "user",
                            content = "Over limit", inputTokens = 0, outputTokens = 0,
                            provider = "openai", syncStatus = "pending", createdAt = 1L
                        )
                        // 4 entries — 3 failures → mark failed; 4th should be skipped
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { localSource.getPendingMessages() } returns
                            listOf(entity, entity, entity, entity)
                        coEvery { remoteSource.sendMessage(any(), any(), any()) } returns
                            ApiResult.Error(DomainError.ServerError("err", 500))

                        repository.syncOfflineQueue()

                        // sendMessage called exactly 3 times (4th skipped)
                        coVerify(exactly = 3) { remoteSource.sendMessage(any(), any(), any()) }
                    }
                }

                it("does not mark message failed on first error (< MAX_RETRY_ATTEMPTS)") {
                    runTest {
                        val entity = com.aiassistant.core.database.entity.MessageEntity(
                            id = "msg-one-fail", conversationId = "conv-1", role = "user",
                            content = "Content", inputTokens = 0, outputTokens = 0,
                            provider = "openai", syncStatus = "pending", createdAt = 1L
                        )
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { localSource.getPendingMessages() } returns listOf(entity)
                        coEvery { remoteSource.sendMessage(any(), any(), any()) } returns
                            ApiResult.Error(DomainError.NetworkError("timeout"))

                        repository.syncOfflineQueue()

                        coVerify(exactly = 0) { localSource.updateSyncStatus(any(), "failed") }
                    }
                }
            }

            describe("connectivity lost mid-sync") {
                it("returns Success with partial count when NetworkUnavailable mid-loop") {
                    runTest {
                        val e1 = com.aiassistant.core.database.entity.MessageEntity(
                            id = "m-ok", conversationId = "conv-1", role = "user",
                            content = "First", inputTokens = 0, outputTokens = 0,
                            provider = "openai", syncStatus = "pending", createdAt = 1L
                        )
                        val e2 = com.aiassistant.core.database.entity.MessageEntity(
                            id = "m-nu", conversationId = "conv-1", role = "user",
                            content = "Second", inputTokens = 0, outputTokens = 0,
                            provider = "openai", syncStatus = "pending", createdAt = 2L
                        )
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { localSource.getPendingMessages() } returns listOf(e1, e2)
                        coEvery { remoteSource.sendMessage("conv-1", "First", "openai") } returns
                            ApiResult.Success(fakeMessageDto())
                        coEvery { remoteSource.sendMessage("conv-1", "Second", "openai") } returns
                            ApiResult.NetworkUnavailable

                        val result = repository.syncOfflineQueue()

                        // First succeeded (1), second hit NetworkUnavailable → return early
                        result shouldBe ApiResult.Success(1)
                    }
                }
            }
        }
    })

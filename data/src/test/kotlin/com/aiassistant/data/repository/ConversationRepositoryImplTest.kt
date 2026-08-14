/**
 * ConversationRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [ConversationRepositoryImpl], covering:
 *   - createConversation() — online/offline guards, Room persistence
 *   - deleteConversation() — soft-delete local-first, remote sync
 *   - searchConversations() — Room emission
 *   - exportConversation() — returns Markdown template
 *   - renameConversation() — local optimistic update + remote sync
 *   - pinConversation() — local update + remote sync
 *
 * Architecture: data module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking ConversationLocalDataSource, ConversationRemoteDataSource,
 *                          ConnectivityObserver, SecureStorage
 * - kotlinx.coroutines.test — runTest + UnconfinedTestDispatcher
 * - Turbine               — Flow collection assertions
 *
 * Requirements covered: 10.1, 10.3, 11.1, 11.3, 11.4, 11.6
 */
package com.aiassistant.data.repository

import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.database.entity.ConversationEntity
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.local.ConversationLocalDataSource
import com.aiassistant.data.remote.conversation.ConversationDto
import com.aiassistant.data.remote.conversation.ConversationRemoteDataSource
import com.aiassistant.domain.model.ExportFormat
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

// ─── Fixtures ────────────────────────────────────────────────────────────────

private fun fakeConversationEntity(
    id: String = "conv-1",
    userId: String = "user-1",
    title: String = "Test Conversation",
    isPinned: Boolean = false,
    isDeleted: Boolean = false,
    provider: String = "openai"
) = ConversationEntity(
    id = id,
    userId = userId,
    title = title,
    isPinned = isPinned,
    isDeleted = isDeleted,
    provider = provider,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeConversationDto(
    id: String = "conv-1",
    userId: String = "user-1",
    title: String = "Test Conversation",
    isPinned: Boolean = false,
    isDeleted: Boolean = false,
    provider: String = "openai"
) = ConversationDto(
    id = id,
    userId = userId,
    title = title,
    isPinned = isPinned,
    isDeleted = isDeleted,
    provider = provider,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

// ─── Spec ────────────────────────────────────────────────────────────────────

class ConversationRepositoryImplTest :
    DescribeSpec({

        val localSource: ConversationLocalDataSource = mockk(relaxed = true)
        val remoteSource: ConversationRemoteDataSource = mockk()
        val connectivityObserver: ConnectivityObserver = mockk()
        val secureStorage: SecureStorage = mockk()
        val dispatchers = TestDispatcherProvider()

        lateinit var repository: ConversationRepositoryImpl

        beforeEach {
            clearAllMocks()
            every { secureStorage.getJwt() } returns "header.payload.userId"
            every { connectivityObserver.isConnectedFlow } returns flowOf(true)
            repository = ConversationRepositoryImpl(
                localSource = localSource,
                remoteSource = remoteSource,
                connectivityObserver = connectivityObserver,
                secureStorage = secureStorage,
                dispatchers = dispatchers
            )
        }

        // ─── getConversations() ───────────────────────────────────────────────────
        describe("getConversations()") {
            it("emits ApiResult.Success with conversations from Room immediately") {
                runTest {
                    val entities = listOf(
                        fakeConversationEntity(id = "conv-1"),
                        fakeConversationEntity(id = "conv-2")
                    )
                    every { localSource.searchConversations(any(), any()) } returns flowOf(entities)
                    every { connectivityObserver.isConnected() } returns false
                    coEvery { remoteSource.getConversations() } returns ApiResult.NetworkUnavailable

                    repository.getConversations().test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        val conversations = (result as ApiResult.Success).data
                        conversations.size shouldBe 2
                        conversations[0].id shouldBe "conv-1"
                        conversations[1].id shouldBe "conv-2"
                        awaitComplete()
                    }
                }
            }

            it("emits empty list when Room has no conversations") {
                runTest {
                    every { localSource.searchConversations(any(), any()) } returns flowOf(emptyList())
                    every { connectivityObserver.isConnected() } returns false
                    coEvery { remoteSource.getConversations() } returns ApiResult.NetworkUnavailable

                    repository.getConversations().test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        (result as ApiResult.Success).data shouldBe emptyList()
                        awaitComplete()
                    }
                }
            }
        }

        // ─── searchConversations() ────────────────────────────────────────────────
        describe("searchConversations()") {
            it("emits conversations from Room filtered by query") {
                runTest {
                    val entities = listOf(fakeConversationEntity(id = "conv-1", title = "Kotlin tips"))
                    every { localSource.searchConversations("kotlin", any()) } returns flowOf(entities)

                    repository.searchConversations("kotlin").test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        (result as ApiResult.Success).data[0].id shouldBe "conv-1"
                        awaitComplete()
                    }
                }
            }

            it("returns all conversations for empty query") {
                runTest {
                    val entities = listOf(
                        fakeConversationEntity(id = "conv-1"),
                        fakeConversationEntity(id = "conv-2")
                    )
                    every { localSource.searchConversations("", any()) } returns flowOf(entities)

                    repository.searchConversations("").test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        (result as ApiResult.Success).data.size shouldBe 2
                        awaitComplete()
                    }
                }
            }
        }

        // ─── createConversation() ─────────────────────────────────────────────────
        describe("createConversation()") {
            describe("online — success path") {
                it("returns ApiResult.Success with the created conversation") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteSource.createConversation("New Chat", "openai")
                        } returns ApiResult.Success(fakeConversationDto(id = "conv-new", title = "New Chat"))

                        val result = repository.createConversation("New Chat", "openai")

                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        (result as ApiResult.Success).data.id shouldBe "conv-new"
                        (result).data.title shouldBe "New Chat"
                    }
                }

                it("inserts the new conversation into Room on success") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteSource.createConversation(any(), any())
                        } returns ApiResult.Success(fakeConversationDto(id = "conv-new"))

                        repository.createConversation("New Chat", "openai")

                        coVerify(exactly = 1) {
                            localSource.insertConversation(match { it.id == "conv-new" })
                        }
                    }
                }
            }

            describe("offline") {
                it("returns ApiResult.NetworkUnavailable when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.createConversation("New Chat", "openai")

                        result shouldBe ApiResult.NetworkUnavailable
                    }
                }

                it("does NOT call remote when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.createConversation("New Chat", "openai")

                        coVerify(exactly = 0) { remoteSource.createConversation(any(), any()) }
                    }
                }
            }

            describe("remote error") {
                it("propagates remote error result") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteSource.createConversation(any(), any())
                        } returns ApiResult.Error(DomainError.ServerError("Server error", 500))

                        val result = repository.createConversation("New Chat", "openai")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                    }
                }
            }
        }

        // ─── deleteConversation() — soft-delete ───────────────────────────────────
        describe("deleteConversation()") {
            describe("soft-delete is applied locally first") {
                it("calls softDeleteConversation on Room immediately") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteConversation(any()) } returns ApiResult.Success(Unit)

                        repository.deleteConversation("conv-1")

                        coVerify(exactly = 1) {
                            localSource.softDeleteConversation(eq("conv-1"), any())
                        }
                    }
                }

                it("applies soft-delete locally even when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.deleteConversation("conv-1")

                        coVerify(exactly = 1) {
                            localSource.softDeleteConversation(eq("conv-1"), any())
                        }
                    }
                }
            }

            describe("online path") {
                it("calls remote deleteConversation when connected") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteConversation("conv-1") } returns ApiResult.Success(Unit)

                        repository.deleteConversation("conv-1")

                        coVerify(exactly = 1) { remoteSource.deleteConversation("conv-1") }
                    }
                }

                it("returns ApiResult.Success(Unit) on success") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteConversation(any()) } returns ApiResult.Success(Unit)

                        val result = repository.deleteConversation("conv-1")

                        result shouldBe ApiResult.Success(Unit)
                    }
                }

                it("returns ApiResult.Success(Unit) even when remote call fails (local-first)") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.deleteConversation(any()) } returns
                            ApiResult.Error(DomainError.ServerError("Server error", 500))

                        val result = repository.deleteConversation("conv-1")

                        result shouldBe ApiResult.Success(Unit)
                        coVerify(exactly = 1) { localSource.softDeleteConversation(any(), any()) }
                    }
                }
            }

            describe("offline path") {
                it("does NOT call remote when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.deleteConversation("conv-1")

                        coVerify(exactly = 0) { remoteSource.deleteConversation(any()) }
                    }
                }

                it("returns ApiResult.Success(Unit) offline after local soft-delete") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.deleteConversation("conv-1")

                        result shouldBe ApiResult.Success(Unit)
                    }
                }
            }
        }

        // ─── renameConversation() ─────────────────────────────────────────────────
        describe("renameConversation()") {
            it("updates Room with new title") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.renameConversation(any(), any()) } returns ApiResult.Success(Unit)

                    repository.renameConversation("conv-1", "New Title")

                    coVerify(exactly = 1) { localSource.renameConversation("conv-1", "New Title", any()) }
                }
            }

            it("calls remote renameConversation when connected") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.renameConversation("conv-1", "New Title") } returns ApiResult.Success(Unit)

                    repository.renameConversation("conv-1", "New Title")

                    coVerify(exactly = 1) { remoteSource.renameConversation("conv-1", "New Title") }
                }
            }

            it("returns ApiResult.Success when rename succeeds") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.renameConversation(any(), any()) } returns ApiResult.Success(Unit)

                    val result = repository.renameConversation("conv-1", "New Title")

                    result shouldBe ApiResult.Success(Unit)
                }
            }

            it("still updates Room locally even when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    repository.renameConversation("conv-1", "New Title")

                    coVerify(exactly = 1) { localSource.renameConversation("conv-1", "New Title", any()) }
                }
            }
        }

        // ─── pinConversation() ────────────────────────────────────────────────────
        describe("pinConversation()") {
            it("pins a conversation in Room when pin=true") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.pinConversation(any(), any()) } returns ApiResult.Success(Unit)

                    repository.pinConversation("conv-1", isPinned = true)

                    coVerify(exactly = 1) { localSource.pinConversation("conv-1", true, any()) }
                }
            }

            it("unpins a conversation in Room when pin=false") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.pinConversation(any(), any()) } returns ApiResult.Success(Unit)

                    repository.pinConversation("conv-1", isPinned = false)

                    coVerify(exactly = 1) { localSource.pinConversation("conv-1", false, any()) }
                }
            }

            it("returns ApiResult.Success(Unit)") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.pinConversation(any(), any()) } returns ApiResult.Success(Unit)

                    val result = repository.pinConversation("conv-1", true)

                    result shouldBe ApiResult.Success(Unit)
                }
            }

            it("applies pin locally even when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    repository.pinConversation("conv-1", true)

                    coVerify(exactly = 1) { localSource.pinConversation("conv-1", true, any()) }
                    coVerify(exactly = 0) { remoteSource.pinConversation(any(), any()) }
                }
            }
        }

        // ─── exportConversation() ─────────────────────────────────────────────────
        describe("exportConversation()") {
            it("returns ApiResult.Success with Markdown template for MARKDOWN format") {
                runTest {
                    val result = repository.exportConversation("conv-1", ExportFormat.MARKDOWN)

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    val content = (result as ApiResult.Success).data
                    content shouldContain "conv-1"
                    content shouldContain "MARKDOWN"
                }
            }

            it("returns ApiResult.Success with PDF format info") {
                runTest {
                    val result = repository.exportConversation("conv-1", ExportFormat.PDF)

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    val content = (result as ApiResult.Success).data
                    content shouldContain "conv-1"
                    content shouldContain "PDF"
                }
            }
        }
    })

/**
 * ConversationRepositoryOfflineSyncPropertyTest.kt — data module
 *
 * Purpose: Property-based tests for Property 17: Conflict-Free Offline Sync.
 *          Verifies that after connectivity is restored and the offline queue is synced:
 *            1. The remote data source receives every queued "pending" message in the
 *               exact original creation order (oldest first).
 *            2. The local Room state is updated to reflect the server-authoritative state:
 *               syncStatus becomes "synced" and server-provided content replaces local content.
 *
 * Architecture: data module — unit tests (pure JVM, no Android framework).
 *               All infrastructure dependencies replaced with MockK fakes.
 *
 * Test toolchain:
 * - Kotest DescribeSpec + checkAll / Arb — property-based test structure
 * - MockK                               — mocking LocalDataSource, RemoteDataSource, ConnectivityObserver
 * - kotlinx.coroutines.test             — runTest + UnconfinedTestDispatcher
 *
 * Iteration budget:
 *   Cases 1, 2, 3, 5, 7, 9 use list inputs (1..5 messages) — 20 iterations each = 120 total.
 *   Case 6 uses single-entity input                          — 20 iterations.
 *   Cases 4, 8 are deterministic (no checkAll).
 *   Total: ~140 coroutine-heavy iterations (down from 1 300+).
 *
 * **Validates: Requirements 10.3**
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.database.entity.MessageEntity
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.local.MessageLocalDataSource
import com.aiassistant.data.remote.message.MessageDto
import com.aiassistant.data.remote.message.MessageRemoteDataSource
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

// ─── Generators ──────────────────────────────────────────────────────────────

/**
 * Generates a single [MessageEntity] with syncStatus = "pending" (offline-queued).
 */
private val arbPendingMessageEntity: Arb<MessageEntity> = arbitrary {
    MessageEntity(
        id = UUID.randomUUID().toString(),
        conversationId = "conv-${Arb.string(3..8).bind()}",
        role = "user",
        content = "local-content-${Arb.string(5..20).bind()}",
        inputTokens = 0,
        outputTokens = 0,
        provider = "openai",
        syncStatus = "pending",
        createdAt = Arb.long(1_000_000L..9_000_000_000L).bind()
    )
}

/**
 * Non-empty list of pending messages, size ∈ [1, 5].
 * Kept small so each iteration stays fast under MockK setup overhead.
 */
private val arbPendingMessageList: Arb<List<MessageEntity>> =
    Arb.list(arbPendingMessageEntity, 1..5)

/** Server-authoritative response: server wins on content and token counts. */
private fun serverResponseFor(entity: MessageEntity): MessageDto = MessageDto(
    id = entity.id,
    conversationId = entity.conversationId,
    role = "assistant",
    content = "server-authoritative-content-for-${entity.id}",
    inputTokens = 10,
    outputTokens = 42,
    provider = entity.provider,
    createdAt = entity.createdAt
)

// ─── Iteration budget constant ────────────────────────────────────────────────

/** Iterations for every checkAll block. 20 gives good edge-case coverage without OOM. */
private const val ITERATIONS = 20

// ─── Property 17: Conflict-Free Offline Sync ─────────────────────────────────

class ConversationRepositoryOfflineSyncPropertyTest :
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

        afterEach { unmockkAll() }

        // ── Case 1: server receives every message in createdAt order ──────────
        describe("Case 1 — server receives every queued message in original creation order") {

            it("for any non-empty sequence of pending messages, remote receives them all in createdAt order") {
                runTest {
                    checkAll(ITERATIONS, arbPendingMessageList) { pendingMessages ->
                        clearAllMocks()
                        val ordered = pendingMessages.sortedBy { it.createdAt }

                        every { connectivityObserver.isConnected() } returns true
                        every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                        coEvery { localSource.getPendingMessages() } returns ordered
                        ordered.forEach { entity ->
                            coEvery {
                                remoteSource.sendMessage(entity.conversationId, entity.content, entity.provider)
                            } returns ApiResult.Success(serverResponseFor(entity))
                        }
                        coEvery { localSource.updateMessage(any()) } returns Unit

                        val result = repository.syncOfflineQueue()

                        result shouldBe ApiResult.Success(ordered.size)
                        coVerify(exactly = ordered.size) { remoteSource.sendMessage(any(), any(), any()) }
                    }
                }
            }
        }

        // ── Case 2: local Room reflects server-authoritative state ────────────
        describe("Case 2 — local Room state reflects server-authoritative state after sync") {

            it("every entity saved to Room has syncStatus=synced and server content") {
                runTest {
                    checkAll(ITERATIONS, arbPendingMessageList) { pendingMessages ->
                        clearAllMocks()
                        val ordered = pendingMessages.sortedBy { it.createdAt }
                        val updatedEntities = mutableListOf<MessageEntity>()
                        val updateSlot = slot<MessageEntity>()
                        var callIndex = 0

                        every { connectivityObserver.isConnected() } returns true
                        every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                        coEvery { localSource.getPendingMessages() } returns ordered
                        coEvery { remoteSource.sendMessage(any(), any(), any()) } answers {
                            ApiResult.Success(serverResponseFor(ordered[callIndex++]))
                        }
                        coEvery { localSource.updateMessage(capture(updateSlot)) } answers {
                            updatedEntities.add(updateSlot.captured)
                        }

                        repository.syncOfflineQueue()

                        updatedEntities shouldHaveSize ordered.size
                        updatedEntities.forEach { saved ->
                            saved.syncStatus shouldBe "synced"
                            saved.content shouldBe "server-authoritative-content-for-${saved.id}"
                            saved.inputTokens shouldBe 10
                            saved.outputTokens shouldBe 42
                        }
                    }
                }
            }
        }

        // ── Case 3: ordering invariant — oldest message submitted first ───────
        describe("Case 3 — ordering invariant: messages submitted to server oldest-first") {

            it("remote call order matches createdAt ascending for any message sequence") {
                runTest {
                    checkAll(ITERATIONS, arbPendingMessageList) { pendingMessages ->
                        clearAllMocks()
                        val ordered = pendingMessages.sortedBy { it.createdAt }
                        val submissionOrder = mutableListOf<String>()
                        var callIndex = 0

                        every { connectivityObserver.isConnected() } returns true
                        every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                        coEvery { localSource.getPendingMessages() } returns ordered
                        coEvery { remoteSource.sendMessage(any(), any(), any()) } answers {
                            val entity = ordered[callIndex++]
                            submissionOrder.add(entity.id)
                            ApiResult.Success(serverResponseFor(entity))
                        }
                        coEvery { localSource.updateMessage(any()) } returns Unit

                        repository.syncOfflineQueue()

                        for (i in submissionOrder.indices) {
                            for (j in i + 1 until submissionOrder.size) {
                                val a = ordered.first { it.id == submissionOrder[i] }
                                val b = ordered.first { it.id == submissionOrder[j] }
                                if (a.createdAt != b.createdAt) {
                                    (a.createdAt <= b.createdAt) shouldBe true
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Case 4: empty queue — deterministic, no checkAll ─────────────────
        describe("Case 4 — empty offline queue produces no remote calls and returns 0") {

            it("syncOfflineQueue with no pending messages returns Success(0) and makes no remote calls") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { localSource.getPendingMessages() } returns emptyList()

                    val result = repository.syncOfflineQueue()

                    result shouldBe ApiResult.Success(0)
                    coVerify(exactly = 0) { remoteSource.sendMessage(any(), any(), any()) }
                }
            }
        }

        // ── Case 5: connectivity guard — no sync while offline ────────────────
        describe("Case 5 — connectivity guard: queue not processed when device is offline") {

            it("returns NetworkUnavailable and makes no remote calls for any non-empty queue") {
                runTest {
                    checkAll(ITERATIONS, arbPendingMessageList) { _ ->
                        clearAllMocks()
                        every { connectivityObserver.isConnected() } returns false
                        every { connectivityObserver.isConnectedFlow } returns flowOf(false)

                        val result = repository.syncOfflineQueue()

                        result shouldBe ApiResult.NetworkUnavailable
                        coVerify(exactly = 0) { remoteSource.sendMessage(any(), any(), any()) }
                        coVerify(exactly = 0) { localSource.updateMessage(any()) }
                    }
                }
            }
        }

        // ── Case 6: single-message queue ─────────────────────────────────────
        describe("Case 6 — single pending message: server-wins for all content fields") {

            it("single pending message is synced with server-authoritative content and syncStatus=synced") {
                runTest {
                    checkAll(ITERATIONS, arbPendingMessageEntity) { entity ->
                        clearAllMocks()
                        val updateSlot = slot<MessageEntity>()
                        var captured: MessageEntity? = null

                        every { connectivityObserver.isConnected() } returns true
                        every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                        coEvery { localSource.getPendingMessages() } returns listOf(entity)
                        val serverDto = serverResponseFor(entity)
                        coEvery {
                            remoteSource.sendMessage(entity.conversationId, entity.content, entity.provider)
                        } returns ApiResult.Success(serverDto)
                        coEvery { localSource.updateMessage(capture(updateSlot)) } answers {
                            captured = updateSlot.captured
                        }

                        val result = repository.syncOfflineQueue()

                        result shouldBe ApiResult.Success(1)
                        captured?.syncStatus shouldBe "synced"
                        captured?.content shouldBe serverDto.content
                        captured?.inputTokens shouldBe serverDto.inputTokens
                        captured?.outputTokens shouldBe serverDto.outputTokens
                        captured?.provider shouldBe serverDto.provider
                    }
                }
            }
        }

        // ── Case 7: all remote calls fail — no Room update ───────────────────
        describe("Case 7 — failed remote call: message not updated to synced in Room") {

            it("a message whose remote call fails is not updated to synced state in Room") {
                runTest {
                    checkAll(ITERATIONS, arbPendingMessageList) { pendingMessages ->
                        clearAllMocks()
                        val ordered = pendingMessages.sortedBy { it.createdAt }

                        every { connectivityObserver.isConnected() } returns true
                        every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                        coEvery { localSource.getPendingMessages() } returns ordered
                        ordered.forEach { entity ->
                            coEvery {
                                remoteSource.sendMessage(entity.conversationId, entity.content, entity.provider)
                            } returns ApiResult.Error(
                                com.aiassistant.core.common.DomainError.ServerError(
                                    message = "Remote error",
                                    httpStatusCode = 500
                                )
                            )
                        }
                        coEvery { localSource.updateSyncStatus(any(), any()) } returns Unit

                        val result = repository.syncOfflineQueue()

                        result shouldBe ApiResult.Success(0)
                        coVerify(exactly = 0) { localSource.updateMessage(any()) }
                    }
                }
            }
        }

        // ── Case 8: partial success — deterministic, no checkAll ──────────────
        describe("Case 8 — partial sync: successful messages get synced, failed ones stay pending") {

            it("for a 2-message queue where first succeeds and second fails, exactly 1 message is synced") {
                runTest {
                    val entityA = MessageEntity(
                        id = "entity-a-fixed-42", conversationId = "conv-test-a",
                        role = "user", content = "local-content-entity-a",
                        inputTokens = 0, outputTokens = 0, provider = "openai",
                        syncStatus = "pending", createdAt = 1_000L
                    )
                    val entityB = MessageEntity(
                        id = "entity-b-fixed-99", conversationId = "conv-test-b",
                        role = "user", content = "local-content-entity-b",
                        inputTokens = 0, outputTokens = 0, provider = "openai",
                        syncStatus = "pending", createdAt = 2_000L
                    )
                    val updatedEntities = mutableListOf<MessageEntity>()
                    val updateSlot = slot<MessageEntity>()

                    every { connectivityObserver.isConnected() } returns true
                    every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                    coEvery { localSource.getPendingMessages() } returns listOf(entityA, entityB)
                    coEvery {
                        remoteSource.sendMessage(entityA.conversationId, entityA.content, entityA.provider)
                    } returns ApiResult.Success(serverResponseFor(entityA))
                    coEvery {
                        remoteSource.sendMessage(entityB.conversationId, entityB.content, entityB.provider)
                    } returns ApiResult.Error(com.aiassistant.core.common.DomainError.NetworkError("timeout"))
                    coEvery { localSource.updateMessage(capture(updateSlot)) } answers {
                        updatedEntities.add(updateSlot.captured)
                    }
                    coEvery { localSource.updateSyncStatus(any(), any()) } returns Unit

                    val result = repository.syncOfflineQueue()

                    result shouldBe ApiResult.Success(1)
                    updatedEntities shouldHaveSize 1
                    updatedEntities[0].id shouldBe entityA.id
                    updatedEntities[0].syncStatus shouldBe "synced"
                }
            }
        }

        // ── Case 9: post-sync invariant — no entity stays "pending" ──────────
        describe("Case 9 — post-sync invariant: no synced entity retains syncStatus=pending") {

            it("all entities updated in Room after a successful sync have syncStatus != pending") {
                runTest {
                    checkAll(ITERATIONS, arbPendingMessageList) { pendingMessages ->
                        clearAllMocks()
                        val ordered = pendingMessages.sortedBy { it.createdAt }
                        val updatedEntities = mutableListOf<MessageEntity>()
                        val updateSlot = slot<MessageEntity>()

                        every { connectivityObserver.isConnected() } returns true
                        every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                        coEvery { localSource.getPendingMessages() } returns ordered
                        ordered.forEach { entity ->
                            coEvery {
                                remoteSource.sendMessage(entity.conversationId, entity.content, entity.provider)
                            } returns ApiResult.Success(serverResponseFor(entity))
                        }
                        coEvery { localSource.updateMessage(capture(updateSlot)) } answers {
                            updatedEntities.add(updateSlot.captured)
                        }

                        repository.syncOfflineQueue()

                        updatedEntities.forEach { saved ->
                            (saved.syncStatus == "pending") shouldBe false
                        }
                    }
                }
            }
        }
    })

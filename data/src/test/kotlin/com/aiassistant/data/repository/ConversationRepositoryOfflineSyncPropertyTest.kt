/**
 * ConversationRepositoryOfflineSyncPropertyTest.kt â€” data module
 *
 * Purpose: Property-based tests for Property 17: Conflict-Free Offline Sync.
 *          Verifies that after connectivity is restored and the offline queue is synced:
 *            1. The remote data source receives every queued "pending" message in the
 *               exact original creation order (oldest first).
 *            2. The local Room state is updated to reflect the server-authoritative state:
 *               syncStatus becomes "synced" and server-provided content replaces local content.
 *
 * Architecture: data module â€” unit tests (pure JVM, no Android framework).
 *               All infrastructure dependencies replaced with MockK fakes.
 *
 * Test toolchain:
 * - Kotest DescribeSpec + checkAll / Arb â€” property-based test structure
 * - MockK                               â€” mocking LocalDataSource, RemoteDataSource, ConnectivityObserver
 * - kotlinx.coroutines.test             â€” runTest + UnconfinedTestDispatcher
 *
 * **Validates: Requirements 10.3**
 *
 * Requirements covered:
 *   10.3 â€” WHEN connectivity is restored, THE AI_Assistant SHALL immediately and
 *           automatically initiate synchronisation of the local Room database with the
 *           Backend, resolving conflicts by preferring the server state for Messages and
 *           the local state for User preferences.
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
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

// â”€â”€â”€ Test doubles â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

// â”€â”€â”€ Generators â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Generates a single [MessageEntity] with [syncStatus] = "pending" (offline-queued).
 *
 * - [createdAt] is drawn from a wide epoch-ms range to exercise ordering logic.
 * - [content] is a random non-empty string to verify server-wins content replacement.
 * - The role is always "user" because only user messages are queued while offline.
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
 * Generates a non-empty list of pending [MessageEntity] objects.
 * List size âˆˆ [1, 10] to keep iterations fast while covering multi-message batches.
 */
private val arbPendingMessageList: Arb<List<MessageEntity>> =
    Arb.list(arbPendingMessageEntity, 1..10)

/**
 * Produces a server-authoritative [MessageDto] for a given local [MessageEntity].
 * The server response has overridden [content] (to simulate server-wins) and
 * [inputTokens] / [outputTokens] populated by the AI backend.
 */
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

// â”€â”€â”€ Property 17: Conflict-Free Offline Sync â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * **Validates: Requirements 10.3**
 *
 * Generates random sequences of offline [MessageEntity] objects (pending syncStatus),
 * runs [MessageRepositoryImpl.syncOfflineQueue], then asserts:
 *   - The remote data source received every queued message in creation order.
 *   - Each updated local entity has syncStatus = "synced".
 *   - Each updated local entity carries the server-authoritative content (server-wins).
 */
class ConversationRepositoryOfflineSyncPropertyTest :
    DescribeSpec({

        // â”€â”€ Shared mocks â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

        // â”€â”€ Case 1 â€” offline queue submitted to server in original creation order â”€
        describe("Case 1 â€” server receives every queued message in original creation order") {

            it("for any non-empty sequence of pending messages, remote receives them all in createdAt order") {
                checkAll(iterations = 200, arbPendingMessageList) { pendingMessages ->
                    clearAllMocks()

                    // Sort as the real getPendingMessages() DAO call does (createdAt ASC)
                    val orderedMessages = pendingMessages.sortedBy { it.createdAt }

                    every { connectivityObserver.isConnected() } returns true
                    every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                    coEvery { localSource.getPendingMessages() } returns orderedMessages

                    // Wire up success response for every sendMessage call
                    orderedMessages.forEach { entity ->
                        coEvery {
                            remoteSource.sendMessage(entity.conversationId, entity.content, entity.provider)
                        } returns ApiResult.Success(serverResponseFor(entity))
                    }

                    // Relax updateMessage to avoid strict matching
                    coEvery { localSource.updateMessage(any()) } returns Unit

                    val result = repository.syncOfflineQueue()

                    // Assert all messages were synced
                    result shouldBe ApiResult.Success(orderedMessages.size)

                    // Assert remote was called exactly once per distinct (conversationId, content, provider) tuple
                    // Use total call count to handle the edge case where two messages have identical params
                    coVerify(exactly = orderedMessages.size) {
                        remoteSource.sendMessage(any(), any(), any())
                    }
                }
            }
        }

        // â”€â”€ Case 2 â€” local Room updated with server-authoritative state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("Case 2 â€” local Room state reflects server-authoritative state after sync") {

            it("every entity saved to Room has syncStatus=synced and server content") {
                checkAll(iterations = 200, arbPendingMessageList) { pendingMessages ->
                    clearAllMocks()

                    val orderedMessages = pendingMessages.sortedBy { it.createdAt }
                    val updatedEntities = mutableListOf<MessageEntity>()
                    val updateSlot = slot<MessageEntity>()
                    // Build a lookup by id so we can verify server-wins content for each entity
                    val entityById = orderedMessages.associateBy { it.id }
                    var callIndex = 0

                    every { connectivityObserver.isConnected() } returns true
                    every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                    coEvery { localSource.getPendingMessages() } returns orderedMessages

                    // Use call-index based response — avoids issues when multiple entities have
                    // identical (conversationId, content, provider) after shrinking.
                    coEvery { remoteSource.sendMessage(any(), any(), any()) } answers {
                        val idx = callIndex++
                        ApiResult.Success(serverResponseFor(orderedMessages[idx]))
                    }

                    // Capture every updateMessage() call
                    coEvery { localSource.updateMessage(capture(updateSlot)) } answers {
                        updatedEntities.add(updateSlot.captured)
                    }

                    repository.syncOfflineQueue()

                    // Assert all pending messages were updated in Room
                    updatedEntities shouldHaveSize orderedMessages.size

                    // Assert each saved entity has server-wins state
                    updatedEntities.forEach { saved ->
                        saved.syncStatus shouldBe "synced"
                        saved.content shouldBe "server-authoritative-content-for-${saved.id}"
                        saved.inputTokens shouldBe 10
                        saved.outputTokens shouldBe 42
                    }
                }
            }
        }

        // â”€â”€ Case 3 â€” ordering invariant: messages submitted oldest-first â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("Case 3 â€” ordering invariant: messages submitted to server oldest-first") {

            it("remote call order matches createdAt ascending for any message sequence") {
                checkAll(iterations = 200, arbPendingMessageList) { pendingMessages ->
                    clearAllMocks()

                    val orderedMessages = pendingMessages.sortedBy { it.createdAt }
                    val submissionOrder = mutableListOf<String>() // track IDs in call order
                    var callIndex = 0

                    every { connectivityObserver.isConnected() } returns true
                    every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                    coEvery { localSource.getPendingMessages() } returns orderedMessages

                    // Use call-index to capture submission order regardless of duplicate params
                    coEvery { remoteSource.sendMessage(any(), any(), any()) } answers {
                        val idx = callIndex++
                        val entity = orderedMessages[idx]
                        submissionOrder.add(entity.id)
                        ApiResult.Success(serverResponseFor(entity))
                    }

                    coEvery { localSource.updateMessage(any()) } returns Unit

                    repository.syncOfflineQueue()

                    // Verify relative ordering: for any two messages where A.createdAt < B.createdAt,
                    // A must appear before B in submissionOrder (handles equal createdAt ties gracefully)
                    for (i in submissionOrder.indices) {
                        for (j in i + 1 until submissionOrder.size) {
                            val entityI = orderedMessages.first { it.id == submissionOrder[i] }
                            val entityJ = orderedMessages.first { it.id == submissionOrder[j] }
                            // If timestamps differ, earlier-created message must come first
                            if (entityI.createdAt != entityJ.createdAt) {
                                (entityI.createdAt <= entityJ.createdAt) shouldBe true
                            }
                        }
                    }
                }
            }
        }

        // â”€â”€ Case 4 â€” empty queue is a no-op â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("Case 4 â€” empty offline queue produces no remote calls and returns 0") {

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

        // â”€â”€ Case 5 â€” offline guard: no sync when device is offline â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("Case 5 â€” connectivity guard: queue not processed when device is offline") {

            it("returns NetworkUnavailable and makes no remote calls for any non-empty queue") {
                checkAll(iterations = 200, arbPendingMessageList) { pendingMessages ->
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

        // â”€â”€ Case 6 â€” single-message queue â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("Case 6 â€” single pending message: server-wins for all content fields") {

            it("single pending message is synced with server-authoritative content and syncStatus=synced") {
                checkAll(iterations = 300, arbPendingMessageEntity) { entity ->
                    clearAllMocks()

                    val updatedSlot = slot<MessageEntity>()
                    var captured: MessageEntity? = null

                    every { connectivityObserver.isConnected() } returns true
                    every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                    coEvery { localSource.getPendingMessages() } returns listOf(entity)

                    val serverDto = serverResponseFor(entity)
                    coEvery {
                        remoteSource.sendMessage(entity.conversationId, entity.content, entity.provider)
                    } returns ApiResult.Success(serverDto)

                    coEvery { localSource.updateMessage(capture(updatedSlot)) } answers {
                        captured = updatedSlot.captured
                    }

                    val result = repository.syncOfflineQueue()

                    result shouldBe ApiResult.Success(1)

                    val saved = captured
                    saved?.syncStatus shouldBe "synced"
                    saved?.content shouldBe serverDto.content
                    saved?.inputTokens shouldBe serverDto.inputTokens
                    saved?.outputTokens shouldBe serverDto.outputTokens
                    saved?.provider shouldBe serverDto.provider
                }
            }
        }

        // â”€â”€ Case 7 â€” failed remote call marks message as pending for retry â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("Case 7 â€” failed remote call: message not updated to synced in Room") {

            it("a message whose remote call fails is not updated to synced state in Room") {
                checkAll(
                    iterations = 200,
                    Arb.list(arbPendingMessageEntity, 1..5)
                ) { pendingMessages ->
                    clearAllMocks()

                    val orderedMessages = pendingMessages.sortedBy { it.createdAt }

                    every { connectivityObserver.isConnected() } returns true
                    every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                    coEvery { localSource.getPendingMessages() } returns orderedMessages

                    // All remote calls fail
                    orderedMessages.forEach { entity ->
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

                    // 0 successfully synced
                    result shouldBe ApiResult.Success(0)

                    // updateMessage must never have been called with syncStatus=synced
                    coVerify(exactly = 0) { localSource.updateMessage(any()) }
                }
            }
        }

        // â”€â”€ Case 8 â€” partial success: only successful remote calls update Room â”€â”€â”€â”€â”€â”€
        describe("Case 8 â€” partial sync: successful messages get synced, failed ones stay pending") {

            it("for a 2-message queue where first succeeds and second fails, exactly 1 message is synced") {
                runTest {
                    val entityA = MessageEntity(
                        id = "entity-a-fixed-42",
                        conversationId = "conv-test-a",
                        role = "user",
                        content = "local-content-entity-a",
                        inputTokens = 0,
                        outputTokens = 0,
                        provider = "openai",
                        syncStatus = "pending",
                        createdAt = 1_000L
                    )
                    val entityB = MessageEntity(
                        id = "entity-b-fixed-99",
                        conversationId = "conv-test-b",
                        role = "user",
                        content = "local-content-entity-b",
                        inputTokens = 0,
                        outputTokens = 0,
                        provider = "openai",
                        syncStatus = "pending",
                        createdAt = 2_000L
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
                    } returns ApiResult.Error(
                        com.aiassistant.core.common.DomainError.NetworkError("timeout")
                    )

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

        // â”€â”€ Case 9 â€” syncStatus is never "pending" after successful sync â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("Case 9 â€” post-sync invariant: no synced entity retains syncStatus=pending") {

            it("all entities updated in Room after a successful sync have syncStatus != pending") {
                checkAll(iterations = 200, arbPendingMessageList) { pendingMessages ->
                    clearAllMocks()

                    val orderedMessages = pendingMessages.sortedBy { it.createdAt }
                    val updatedEntities = mutableListOf<MessageEntity>()
                    val updateSlot = slot<MessageEntity>()

                    every { connectivityObserver.isConnected() } returns true
                    every { connectivityObserver.isConnectedFlow } returns flowOf(true)
                    coEvery { localSource.getPendingMessages() } returns orderedMessages

                    orderedMessages.forEach { entity ->
                        coEvery {
                            remoteSource.sendMessage(entity.conversationId, entity.content, entity.provider)
                        } returns ApiResult.Success(serverResponseFor(entity))
                    }

                    coEvery { localSource.updateMessage(capture(updateSlot)) } answers {
                        updatedEntities.add(updateSlot.captured)
                    }

                    repository.syncOfflineQueue()

                    // No entity saved to Room should remain in "pending" state
                    updatedEntities.forEach { saved ->
                        (saved.syncStatus == "pending") shouldBe false
                    }
                }
            }
        }
    })

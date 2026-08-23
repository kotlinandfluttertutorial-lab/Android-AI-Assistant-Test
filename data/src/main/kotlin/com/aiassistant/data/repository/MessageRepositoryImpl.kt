/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MessageRepositoryImpl.kt
 * Purpose    : Implements MessageRepository with Room (local) and Retrofit (remote) data sources
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation (offline-first)
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MessageRepositoryImpl.kt
 * Purpose    : Implements MessageRepository with Room (local) and Retrofit (remote) data sources
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation (offline-first)
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * MessageRepositoryImpl.kt â€” data module
 *
 * Purpose: Production implementation of [MessageRepository]. Orchestrates
 *          [MessageLocalDataSource] (Room) and [MessageRemoteDataSource] (Retrofit) with
 *          an offline-first strategy: always persist locally first, then sync when online.
 *
 * Architecture: data module â€” repository layer. Domain layer has zero knowledge of this
 *               class; wired at runtime via [ConversationDataModule] Hilt bindings.
 * Dependencies:
 *   - MessageLocalDataSource (Room DAO wrapper)
 *   - MessageRemoteDataSource (Retrofit service wrapper)
 *   - ConnectivityObserver (connectivity gating for all remote calls)
 *   - DispatcherProvider (coroutine dispatcher injection)
 *
 * Offline queue pattern (Requirements 10.2, 10.6):
 *   - Messages created while offline are persisted with [syncStatus] = "pending".
 *   - [syncOfflineQueue] replays all pending messages in original creation order.
 *   - After [MAX_RETRY_ATTEMPTS] failures the message is marked [syncStatus] = "failed".
 *
 * Server-wins conflict resolution for message content (Requirement 10.3):
 *   - During [syncOfflineQueue] the server response overwrites local message [content],
 *     [inputTokens], [outputTokens], and [provider] fields.
 *   - [syncStatus] is always set to "synced" after a successful remote call.
 *
 * Requirements: 2.6, 10.2, 10.3, 10.6
 */
package com.aiassistant.data.repository

import android.util.Log
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.local.MessageLocalDataSource
import com.aiassistant.data.mapper.toDomain
import com.aiassistant.data.mapper.toEntity
import com.aiassistant.data.remote.message.MessageRemoteDataSource
import com.aiassistant.domain.model.Message
import com.aiassistant.domain.repository.MessageRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

private const val TAG = "MessageRepository"

/**
 * Maximum number of retry attempts for queued offline messages (Requirement 10.6).
 * After this many consecutive failures the message is marked "failed".
 */
private const val MAX_RETRY_ATTEMPTS = 3

/**
 * Offline-first implementation of [MessageRepository].
 *
 * Room is the single source of truth. Every send/receive operation persists locally
 * before any network call. [ConnectivityObserver] gates all remote calls.
 *
 * @param localSource          Room-backed local data source.
 * @param remoteSource         Retrofit-backed remote data source.
 * @param connectivityObserver Synchronous connectivity snapshot for remote-call gating.
 * @param dispatchers          Injected dispatcher provider.
 */
@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val localSource: MessageLocalDataSource,
    private val remoteSource: MessageRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : MessageRepository {

    // â”€â”€â”€ MessageRepository â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Sends a user message (Requirement 10.2).
     *
     * Steps:
     * 1. Persist user message locally with [syncStatus] = "pending".
     * 2. If online: POST to backend; on success persist AI response locally with
     *    [syncStatus] = "synced" and update user message status to "synced".
     * 3. If offline: return the locally-persisted user message with [syncStatus] = "pending".
     *    WorkManager ([SyncMessagesWorker]) will retry when connectivity is restored.
     *
     * @param conversationId Conversation this message belongs to.
     * @param content        User message text.
     * @param provider       The LLM provider identifier.
     * @return [ApiResult.Success] with the persisted [Message] on success.
     */
    override suspend fun sendMessage(conversationId: String, content: String, provider: String): ApiResult<Message> =
        withContext(dispatchers.io) {
            // 1. Persist user message locally (offline-first â€” Requirement 10.1)
            val userMessage = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "user",
                content = content,
                inputTokens = 0,
                outputTokens = 0,
                provider = provider,
                syncStatus = "pending",
                createdAt = Instant.now()
            )
            localSource.insertMessage(userMessage.toEntity(syncStatus = "pending"))

            // 2. Guard: skip remote call when offline
            if (!connectivityObserver.isConnected()) {
                Log.d(TAG, "sendMessage: offline â€” message queued as pending (id=${userMessage.id}).")
                return@withContext ApiResult.NetworkUnavailable
            }

            // 3. Online: POST to backend
            when (val result = remoteSource.sendMessage(conversationId, content, provider)) {
                is ApiResult.Success -> {
                    val dto = result.data

                    // Update user message to synced
                    localSource.updateSyncStatus(userMessage.id, "synced")

                    // Persist the AI response (server-wins for content â€” Requirement 10.3)
                    val aiMessageEntity = dto.toEntity(conversationId = conversationId)
                    localSource.insertMessage(aiMessageEntity)

                    ApiResult.Success(aiMessageEntity.toDomain())
                }
                is ApiResult.Error -> {
                    // Remote send failed â€” keep message as "pending" for WorkManager retry
                    Log.w(TAG, "sendMessage: remote call failed â€” ${result.error.message}")
                    result
                }
                is ApiResult.NetworkUnavailable -> {
                    Log.d(TAG, "sendMessage: became offline mid-flight.")
                    ApiResult.NetworkUnavailable
                }
                is ApiResult.Loading -> ApiResult.Loading
            }
        }

    /**
     * Requests a regenerated response for an existing assistant message (Requirement 2.6).
     *
     * The regenerated response is appended as an alternative to the existing message.
     * Requires connectivity â€” returns [ApiResult.NetworkUnavailable] when offline.
     *
     * @param conversationId    The conversation identifier.
     * @param originalMessageId The message identifier to regenerate from.
     * @return [ApiResult.Success] with the new [Message] on success.
     */
    override suspend fun regenerateMessage(conversationId: String, originalMessageId: String): ApiResult<Message> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

            when (val result = remoteSource.regenerateMessage(conversationId, originalMessageId)) {
                is ApiResult.Success -> {
                    val entity = result.data.toEntity(conversationId = conversationId)
                    localSource.insertMessage(entity)
                    ApiResult.Success(entity.toDomain())
                }
                is ApiResult.Error -> result
                is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
                is ApiResult.Loading -> ApiResult.Loading
            }
        }

    /**
     * Submits all "pending" messages to the backend in creation order (Requirement 10.2).
     *
     * Called by WorkManager's `SyncMessagesWorker` when connectivity is restored.
     *
     * Conflict resolution (server-wins for content, Requirement 10.3):
     *   - On a successful remote call the local message content, tokens, and provider are
     *     updated with the server response values.
     *   - syncStatus transitions to "synced".
     *
     * Retry limit (Requirement 10.6):
     *   - Each message tracks consecutive failures via the [failureCounts] map within this
     *     invocation. After [MAX_RETRY_ATTEMPTS] failures the message is marked "failed".
     *
     * @return [ApiResult.Success] with the count of successfully synced messages.
     */
    override suspend fun syncOfflineQueue(): ApiResult<Int> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) {
            Log.d(TAG, "syncOfflineQueue: offline â€” skipping.")
            return@withContext ApiResult.NetworkUnavailable
        }

        val pending = localSource.getPendingMessages()
        if (pending.isEmpty()) {
            Log.d(TAG, "syncOfflineQueue: no pending messages.")
            return@withContext ApiResult.Success(0)
        }

        Log.d(TAG, "syncOfflineQueue: syncing ${pending.size} pending message(s).")

        var syncedCount = 0
        val failureCounts = mutableMapOf<String, Int>()

        for (entity in pending) {
            val attemptCount = failureCounts.getOrDefault(entity.id, 0)
            if (attemptCount >= MAX_RETRY_ATTEMPTS) {
                // Exceeded retry budget â€” mark as failed
                localSource.updateSyncStatus(entity.id, "failed")
                Log.w(
                    TAG,
                    "syncOfflineQueue: message ${entity.id} exceeded $MAX_RETRY_ATTEMPTS attempts â€” marked failed."
                )
                continue
            }

            when (
                val result = remoteSource.sendMessage(
                    conversationId = entity.conversationId,
                    content = entity.content,
                    provider = entity.provider
                )
            ) {
                is ApiResult.Success -> {
                    val dto = result.data
                    // Server-wins: update the local message with server-authoritative content
                    val updatedEntity = entity.copy(
                        content = dto.content,
                        inputTokens = dto.inputTokens,
                        outputTokens = dto.outputTokens,
                        provider = dto.provider,
                        syncStatus = "synced"
                    )
                    localSource.updateMessage(updatedEntity)
                    syncedCount++
                    Log.d(TAG, "syncOfflineQueue: message ${entity.id} synced successfully.")
                }
                is ApiResult.Error -> {
                    val newCount = attemptCount + 1
                    failureCounts[entity.id] = newCount
                    if (newCount >= MAX_RETRY_ATTEMPTS) {
                        localSource.updateSyncStatus(entity.id, "failed")
                        Log.w(
                            TAG,
                            "syncOfflineQueue: message ${entity.id} failed after $newCount attempt(s) â€” marked failed."
                        )
                    } else {
                        Log.w(
                            TAG,
                            "syncOfflineQueue: message ${entity.id} attempt $newCount failed: ${result.error.message}"
                        )
                    }
                }
                is ApiResult.NetworkUnavailable -> {
                    // Lost connectivity mid-sync â€” stop processing the queue
                    Log.d(TAG, "syncOfflineQueue: lost connectivity during sync. Stopping.")
                    return@withContext ApiResult.Success(syncedCount)
                }
                is ApiResult.Loading -> Unit
            }
        }

        Log.d(TAG, "syncOfflineQueue: completed â€” $syncedCount/${pending.size} message(s) synced.")
        ApiResult.Success(syncedCount)
    }
}

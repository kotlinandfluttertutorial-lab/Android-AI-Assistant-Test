/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MessageLocalDataSource.kt
 * Purpose    : MessageLocalDataSource — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Data Source (local or remote)
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
 * File       : MessageLocalDataSource.kt
 * Purpose    : MessageLocalDataSource — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Data Source (local or remote)
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
 * MessageLocalDataSource.kt â€” data module
 *
 * Purpose: Wrapper around [MessageDao] that exposes a stable API for the repository
 *          layer. Keeps raw DAO operations out of the repository and ensures all
 *          database work executes on the I/O dispatcher.
 *
 * Architecture: data module â€” local data source layer. Consumed by
 *               [com.aiassistant.data.repository.MessageRepositoryImpl].
 * Dependencies: MessageDao, DispatcherProvider
 *
 * Requirements: 10.1, 10.2, 10.3
 */
package com.aiassistant.data.local

import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.database.dao.MessageDao
import com.aiassistant.core.database.entity.MessageEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Local data source for message data backed by Room.
 *
 * Room [Flow] emissions already observe on the correct Room thread; suspend functions
 * that mutate state are wrapped in [DispatcherProvider.io].
 *
 * @param messageDao  DAO for [MessageEntity] CRUD operations.
 * @param dispatchers Injectable dispatcher provider for suspend mutations.
 */
@Singleton
class MessageLocalDataSource @Inject constructor(
    private val messageDao: MessageDao,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Returns a [Flow] of all messages in [conversationId], ordered by [MessageEntity.createdAt]
     * ascending (oldest message first, matches typical chat display order).
     */
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(conversationId)

    /**
     * Inserts or replaces a single message entity in Room.
     */
    suspend fun insertMessage(entity: MessageEntity) = withContext(dispatchers.io) {
        messageDao.insertMessage(entity)
    }

    /**
     * Inserts or replaces a list of message entities in Room (used for bulk sync).
     */
    suspend fun insertMessages(entities: List<MessageEntity>) = withContext(dispatchers.io) {
        messageDao.insertMessages(entities)
    }

    /**
     * Updates an existing message entity (e.g. after a server-wins conflict resolution
     * overwrites local content per Requirement 10.3).
     */
    suspend fun updateMessage(entity: MessageEntity) = withContext(dispatchers.io) {
        messageDao.updateMessage(entity)
    }

    /**
     * Returns all messages whose [MessageEntity.syncStatus] is "pending", ordered by
     * [MessageEntity.createdAt] ascending so the offline queue is replayed in original order.
     *
     * Used by [com.aiassistant.data.repository.MessageRepositoryImpl.syncOfflineQueue].
     */
    suspend fun getPendingMessages(): List<MessageEntity> = withContext(dispatchers.io) {
        messageDao.getPendingMessages()
    }

    /**
     * Updates the [MessageEntity.syncStatus] for a single message.
     *
     * Status transitions:
     *   "pending" â†’ "synced"  (successful backend delivery)
     *   "pending" â†’ "failed"  (delivery failed after [MAX_RETRY_ATTEMPTS])
     *
     * @param id     The message identifier.
     * @param status The new sync status string ("synced" | "pending" | "failed").
     */
    suspend fun updateSyncStatus(id: String, status: String) = withContext(dispatchers.io) {
        messageDao.updateSyncStatus(id, status)
    }
}

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ConversationLocalDataSource.kt
 * Purpose    : ConversationLocalDataSource — data module component
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
 * File       : ConversationLocalDataSource.kt
 * Purpose    : ConversationLocalDataSource — data module component
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
 * ConversationLocalDataSource.kt â€” data module
 *
 * Purpose: Wrapper around [ConversationDao] that exposes a stable API for the
 *          repository layer. Keeps raw DAO operations out of the repository and
 *          ensures all database work executes on the I/O dispatcher.
 *
 * Architecture: data module â€” local data source layer. Consumed by
 *               [com.aiassistant.data.repository.ConversationRepositoryImpl].
 * Dependencies: ConversationDao, MessageDao, DispatcherProvider
 *
 * Requirements: 10.1, 11.1
 */
package com.aiassistant.data.local

import androidx.paging.PagingSource
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.database.dao.ConversationDao
import com.aiassistant.core.database.entity.ConversationEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Local data source for conversation data backed by Room.
 *
 * Room [Flow] and [PagingSource] emissions already observe on the correct Room thread;
 * the suspend functions that mutate state are wrapped in [DispatcherProvider.io].
 *
 * @param conversationDao DAO for [ConversationEntity] CRUD operations and FTS search.
 * @param dispatchers     Injectable dispatcher provider for suspend mutations.
 */
@Singleton
class ConversationLocalDataSource @Inject constructor(
    private val conversationDao: ConversationDao,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Returns a [PagingSource] of non-deleted conversations for [userId], ordered by
     * [ConversationEntity.updatedAt] descending (Requirement 11.1).
     *
     * Used by Paging 3 to load 20 items per page.
     */
    fun getConversationsPaged(userId: String): PagingSource<Int, ConversationEntity> =
        conversationDao.getConversations(userId)

    /**
     * Returns a [Flow] of non-deleted conversations matching [query] via FTS4.
     * An empty query returns all conversations (Requirement 11.2).
     */
    fun searchConversations(query: String, userId: String): Flow<List<ConversationEntity>> =
        conversationDao.searchConversations(query, userId)

    /**
     * Returns a [Flow] that emits a single [ConversationEntity] or null for [id].
     */
    fun getConversationById(id: String): Flow<ConversationEntity?> = conversationDao.getConversationById(id)

    /**
     * Inserts or replaces a conversation entity in Room.
     */
    suspend fun insertConversation(entity: ConversationEntity) = withContext(dispatchers.io) {
        conversationDao.insertConversation(entity)
    }

    /**
     * Inserts or replaces a list of conversation entities in Room (used for bulk sync).
     */
    suspend fun insertConversations(entities: List<ConversationEntity>) = withContext(dispatchers.io) {
        conversationDao.insertConversations(entities)
    }

    /**
     * Marks a conversation as soft-deleted locally (Requirement 11.4).
     *
     * @param id        The conversation identifier.
     * @param updatedAt Epoch milliseconds of the deletion timestamp.
     */
    suspend fun softDeleteConversation(id: String, updatedAt: Long) = withContext(dispatchers.io) {
        conversationDao.softDeleteConversation(id, updatedAt)
    }

    /**
     * Renames a conversation in the local Room database (Requirement 11.3).
     */
    suspend fun renameConversation(id: String, newTitle: String, updatedAt: Long) = withContext(dispatchers.io) {
        conversationDao.renameConversation(id, newTitle, updatedAt)
    }

    /**
     * Pins or unpins a conversation in the local Room database (Requirement 11.3).
     */
    suspend fun pinConversation(id: String, isPinned: Boolean, updatedAt: Long) = withContext(dispatchers.io) {
        conversationDao.pinConversation(id, isPinned, updatedAt)
    }

    /**
     * Updates an existing conversation entity (e.g. after a server-wins conflict resolution).
     */
    suspend fun updateConversation(entity: ConversationEntity) = withContext(dispatchers.io) {
        conversationDao.updateConversation(entity)
    }
}

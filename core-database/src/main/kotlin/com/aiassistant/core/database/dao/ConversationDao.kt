/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : ConversationDao.kt
 * Purpose    : Room DAO interface defining SQL queries for Conversation entities
 *
 * Architecture Layer : Core-Database
 * Pattern Used       : Room DAO Interface
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
 * Module     : core-database
 * File       : ConversationDao.kt
 * Purpose    : Room DAO interface defining SQL queries for Conversation entities
 *
 * Architecture Layer : Core-Database
 * Pattern Used       : Room DAO Interface
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
package com.aiassistant.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aiassistant.core.database.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations WHERE userId = :userId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun getConversations(userId: String): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id AND isDeleted = 0")
    fun getConversationById(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET isDeleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDeleteConversation(id: String, updatedAt: Long)

    @Query("UPDATE conversations SET title = :newTitle, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameConversation(id: String, newTitle: String, updatedAt: Long)

    @Query("UPDATE conversations SET isPinned = :isPinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun pinConversation(id: String, isPinned: Boolean, updatedAt: Long)

    @Query(
        """
        SELECT conversations.* FROM conversations
        JOIN conversations_fts ON conversations.id = conversations_fts.rowid
        WHERE conversations_fts MATCH :query
          AND conversations.userId = :userId
          AND conversations.isDeleted = 0
        ORDER BY conversations.updatedAt DESC
        """
    )
    fun searchConversations(query: String, userId: String): Flow<List<ConversationEntity>>
}

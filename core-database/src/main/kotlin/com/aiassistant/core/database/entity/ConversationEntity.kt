/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : ConversationEntity.kt
 * Purpose    : Room entity class representing the Conversation database table
 *
 * Architecture Layer : Core-Database
 * Pattern Used       : Room Entity
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
 * File       : ConversationEntity.kt
 * Purpose    : Room entity class representing the Conversation database table
 *
 * Architecture Layer : Core-Database
 * Pattern Used       : Room Entity
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
package com.aiassistant.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val isPinned: Boolean,
    val isDeleted: Boolean,
    val provider: String,
    val createdAt: Long,
    val updatedAt: Long
)

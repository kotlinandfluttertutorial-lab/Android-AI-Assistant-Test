/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : ConversationFtsEntity.kt
 * Purpose    : Room entity class representing the ConversationFts database table
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
 * File       : ConversationFtsEntity.kt
 * Purpose    : Room entity class representing the ConversationFts database table
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
import androidx.room.Fts4

@Fts4(contentEntity = ConversationEntity::class)
@Entity(tableName = "conversations_fts")
data class ConversationFtsEntity(val title: String)

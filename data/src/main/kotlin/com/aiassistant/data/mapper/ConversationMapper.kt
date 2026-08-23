/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ConversationMapper.kt
 * Purpose    : ConversationMapper — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Domain / Entity Mapper
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
 * File       : ConversationMapper.kt
 * Purpose    : ConversationMapper — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Domain / Entity Mapper
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
 * ConversationMapper.kt â€” data module
 *
 * Purpose: Extension functions for bidirectional mapping between [ConversationEntity]
 *          (Room / persistence layer) and [Conversation] (domain layer).
 *
 * Architecture: data module â€” mapper layer. All mappings are pure functions with no
 *               side effects. Consumed by [ConversationRepositoryImpl].
 * Dependencies: core-database (ConversationEntity), domain (Conversation)
 *
 * Design decisions:
 * - Extension functions on the entity type (entity.toDomain()) keep the syntax ergonomic.
 * - epoch-millisecond timestamps are converted to [java.time.Instant] in the domain model
 *   to keep domain entities framework-free while Room stores plain Long values.
 * - [Conversation.toEntity] preserves [isDeleted] from the domain model so soft-delete
 *   state round-trips correctly through persistence.
 *
 * Requirements: 10.1, 10.3, 11.1
 */
package com.aiassistant.data.mapper

import com.aiassistant.core.database.entity.ConversationEntity
import com.aiassistant.domain.model.Conversation
import java.time.Instant

/**
 * Maps a [ConversationEntity] (Room) to a [Conversation] (domain model).
 *
 * Converts stored epoch-millisecond timestamps to [Instant] values so the domain
 * layer never depends on primitive Long timestamps.
 *
 * @return The domain representation of this entity.
 */
fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    userId = userId,
    title = title,
    isPinned = isPinned,
    isDeleted = isDeleted,
    provider = provider,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt)
)

/**
 * Maps a [Conversation] (domain model) to a [ConversationEntity] (Room).
 *
 * Converts [Instant] timestamps back to epoch milliseconds for Room storage.
 *
 * @return The persistence representation of this domain model.
 */
fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    userId = userId,
    title = title,
    isPinned = isPinned,
    isDeleted = isDeleted,
    provider = provider,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)

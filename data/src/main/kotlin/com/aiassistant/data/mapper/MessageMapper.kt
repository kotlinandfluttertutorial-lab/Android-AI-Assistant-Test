/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MessageMapper.kt
 * Purpose    : MessageMapper — data module component
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
 * File       : MessageMapper.kt
 * Purpose    : MessageMapper — data module component
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
 * MessageMapper.kt â€” data module
 *
 * Purpose: Extension functions for bidirectional mapping between [MessageEntity]
 *          (Room / persistence layer) and [Message] (domain layer). Also maps
 *          [MessageDto] (Retrofit / remote layer) â†’ [MessageEntity] â†’ [Message].
 *
 * Architecture: data module â€” mapper layer. All mappings are pure functions with no
 *               side effects. Consumed by [MessageRepositoryImpl].
 * Dependencies: core-database (MessageEntity), domain (Message), remote (MessageDto)
 *
 * Design decisions:
 * - epoch-millisecond timestamps are converted to [java.time.Instant] in the domain
 *   model to keep domain entities framework-free.
 * - [Message.toEntity] accepts an explicit [syncStatus] parameter because the caller
 *   determines whether to persist as "pending" (offline), "synced" (confirmed), or
 *   "failed" (permanent delivery failure). The domain model carries the current status
 *   value as a default so callers may also omit it to preserve the existing status.
 * - [MessageDto.toEntity] always produces an entity with syncStatus = "synced" because
 *   DTOs from the remote represent server-authoritative state (server-wins policy for
 *   message content, Requirement 10.3).
 *
 * Requirements: 10.1, 10.2, 10.3
 */
package com.aiassistant.data.mapper

import com.aiassistant.core.database.entity.MessageEntity
import com.aiassistant.data.remote.message.MessageDto
import com.aiassistant.domain.model.Message
import java.time.Instant

/**
 * Maps a [MessageEntity] (Room) to a [Message] (domain model).
 *
 * @return The domain representation of this entity.
 */
fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    provider = provider,
    syncStatus = syncStatus,
    createdAt = Instant.ofEpochMilli(createdAt)
)

/**
 * Maps a [Message] (domain model) to a [MessageEntity] (Room).
 *
 * @param syncStatus Override the sync status. Defaults to the message's own [Message.syncStatus]
 *                   so that persisting an already-synced domain object preserves its status.
 * @return The persistence representation of this domain model.
 */
fun Message.toEntity(syncStatus: String = this.syncStatus): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    provider = provider,
    syncStatus = syncStatus,
    createdAt = createdAt.toEpochMilli()
)

/**
 * Maps a [MessageDto] (remote/Retrofit) to a [MessageEntity] (Room).
 *
 * DTOs from the backend are always treated as server-authoritative ("synced") because
 * they represent confirmed server state (server-wins conflict resolution, Requirement 10.3).
 *
 * @param conversationId The conversation this message belongs to. Present in the DTO
 *                       but explicitly required here so the mapping is unambiguous.
 * @return The persistence entity ready for Room insertion.
 */
fun MessageDto.toEntity(conversationId: String): MessageEntity = MessageEntity(
    id = id,
    conversationId = this.conversationId.ifEmpty { conversationId },
    role = role,
    content = content,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    provider = provider,
    syncStatus = "synced",
    createdAt = createdAt
)

/**
 * Convenience: maps a [MessageDto] directly to the [Message] domain model.
 *
 * The resulting message carries syncStatus = "synced" (server-authoritative).
 */
fun MessageDto.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    provider = provider,
    syncStatus = "synced",
    createdAt = Instant.ofEpochMilli(createdAt)
)

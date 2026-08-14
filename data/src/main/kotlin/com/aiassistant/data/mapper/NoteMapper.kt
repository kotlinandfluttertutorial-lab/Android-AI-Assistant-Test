/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : NoteMapper.kt
 * Purpose    : NoteMapper — data module component
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
 * File       : NoteMapper.kt
 * Purpose    : NoteMapper — data module component
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
 * NoteMapper.kt â€” data module
 *
 * Purpose: Bidirectional mapping between [NoteEntity] (Room) and [Note] (domain),
 *          and between [NoteDto] (Retrofit) and [NoteEntity] / [Note].
 *
 * Architecture: data module â€” mapper layer. Pure functions, no side effects.
 * Dependencies: core-database (NoteEntity), domain (Note, SyncStatus),
 *               data.remote.note (NoteDto)
 *
 * Design: [NoteEntity.tags] is a JSON array stored as a plain String. This mapper
 *         handles serialization/deserialization using kotlinx.serialization.
 *
 * Requirements: 13.1, 13.4, 13.5
 */
package com.aiassistant.data.mapper

import com.aiassistant.core.database.entity.NoteEntity
import com.aiassistant.data.remote.note.NoteDto
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.SyncStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val jsonParser = Json { ignoreUnknownKeys = true }

// â”€â”€â”€ Entity â†” Domain â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Maps a [NoteEntity] (Room) to a [Note] (domain model).
 * Deserializes the JSON [NoteEntity.tags] string to a [List<String>].
 */
fun NoteEntity.toDomain(): Note = Note(
    id = id,
    userId = userId,
    title = title,
    content = content,
    tags = decodeTags(tags),
    syncStatus = SyncStatus.fromValue(syncStatus),
    createdAt = createdAt,
    updatedAt = updatedAt
)

/**
 * Maps a [Note] (domain model) to a [NoteEntity] (Room).
 * Serializes [Note.tags] to a JSON string for Room storage.
 */
fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id,
    userId = userId,
    title = title,
    content = content,
    tags = encodeTags(tags),
    syncStatus = syncStatus.value,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// â”€â”€â”€ DTO â†’ Domain â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Maps a [NoteDto] (Retrofit) to a [Note] (domain model).
 */
fun NoteDto.toDomain(): Note = Note(
    id = id,
    userId = userId,
    title = title,
    content = content,
    tags = tags,
    syncStatus = SyncStatus.fromValue(syncStatus),
    createdAt = createdAt,
    updatedAt = updatedAt
)

/**
 * Maps a [NoteDto] (Retrofit) to a [NoteEntity] (Room).
 */
fun NoteDto.toEntity(): NoteEntity = NoteEntity(
    id = id,
    userId = userId,
    title = title,
    content = content,
    tags = encodeTags(tags),
    syncStatus = syncStatus,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// â”€â”€â”€ JSON helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Serializes a tag list to a JSON string for Room storage. */
fun encodeTags(tags: List<String>): String = jsonParser.encodeToString(ListSerializer(String.serializer()), tags)

/** Deserializes a JSON tag string from Room back to a list. Returns empty list on failure. */
fun decodeTags(tagsJson: String): List<String> = try {
    jsonParser.decodeFromString(ListSerializer(String.serializer()), tagsJson)
} catch (_: Exception) {
    emptyList()
}

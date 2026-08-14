/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : DocumentMapper.kt
 * Purpose    : DocumentMapper — data module component
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
 * File       : DocumentMapper.kt
 * Purpose    : DocumentMapper — data module component
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
 * DocumentMapper.kt â€” data module
 *
 * Purpose: Bidirectional mapping between [DocumentEntity] (Room) and [Document] (domain),
 *          and between [DocumentDto] (Retrofit) and [DocumentEntity] / [Document].
 *
 * Architecture: data module â€” mapper layer. Pure functions, no side effects.
 * Dependencies: core-database (DocumentEntity), domain (Document, IngestionStatus),
 *               data.remote.document (DocumentDto)
 *
 * Requirements: 4.1, 4.10
 */
package com.aiassistant.data.mapper

import com.aiassistant.core.database.entity.DocumentEntity
import com.aiassistant.data.remote.document.DocumentDto
import com.aiassistant.data.remote.document.DocumentUploadResponseDto
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.IngestionStatus
import java.time.Instant

// ─── Entity ↔ Domain ─────────────────────────────────────────────────────────────

/**
 * Maps a [DocumentEntity] (Room) to a [Document] (domain model).
 */
fun DocumentEntity.toDomain(): Document = Document(
    id = id,
    userId = userId,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    ingestionStatus = IngestionStatus.fromValue(ingestionStatus),
    jobId = jobId,
    pageCount = pageCount,
    createdAt = createdAt
)

/**
 * Maps a [Document] (domain model) to a [DocumentEntity] (Room).
 */
fun Document.toEntity(): DocumentEntity = DocumentEntity(
    id = id,
    userId = userId,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    ingestionStatus = ingestionStatus.value,
    jobId = jobId,
    pageCount = pageCount,
    createdAt = createdAt
)

// ─── DTO → Entity ─────────────────────────────────────────────────────────────

/**
 * Maps a [DocumentDto] (Retrofit) to a [DocumentEntity] (Room).
 *
 * @param userId The authenticated user ID (not provided in the DTO).
 */
fun DocumentDto.toEntity(userId: String): DocumentEntity = DocumentEntity(
    id = id,
    userId = userId,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    ingestionStatus = ingestionStatus,
    jobId = null, // DocumentDto doesn't include jobId
    pageCount = pageCount,
    createdAt = try {
        Instant.parse(createdAt).toEpochMilli()
    } catch (e: Exception) {
        0L
    }
)

/**
 * Maps a [DocumentUploadResponseDto] and request metadata to a [DocumentEntity].
 */
fun DocumentUploadResponseDto.toEntity(
    userId: String,
    fileName: String,
    mimeType: String,
    sizeBytes: Long
): DocumentEntity = DocumentEntity(
    id = documentId,
    userId = userId,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    ingestionStatus = status,
    jobId = jobId,
    pageCount = null,
    createdAt = System.currentTimeMillis()
)

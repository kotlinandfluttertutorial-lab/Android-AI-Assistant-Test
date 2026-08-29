/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : OnDeviceDocumentEntity.kt
 * Purpose    : Room entity representing a user document that has been ingested
 *              into the on-device RAG pipeline.  Tracks ingestion lifecycle
 *              (pending → processing → ready | failed) and storage metadata
 *              so the UI can display accurate status badges and chunk counts.
 *
 * Architecture Layer : Core-Database — persistence layer.
 *                      Mapped to/from domain's OnDeviceDocument by the data
 *                      module; never exposed directly to feature modules.
 *
 * Dependencies       : Room, core-database entity conventions
 *
 * Design Decision    : Ingestion status is stored as a plain String column
 *                      (not an enum) so SQLite migrations can add new status
 *                      values without a schema change.  The domain layer
 *                      defines the authoritative IngestionStatus sealed class.
 *                      failureStage is nullable — it is only populated when
 *                      ingestionStatus == "failed" and records which pipeline
 *                      stage threw: "extraction" | "chunking" | "embedding".
 * ============================================================
 */
package com.aiassistant.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persists metadata for every document submitted to the on-device RAG pipeline.
 *
 * @param id              UUID, primary key.
 * @param userId          Owner of the document — enforced at SQL layer for isolation.
 * @param fileName        Original file name shown in the UI.
 * @param mimeType        MIME type: "application/pdf", "text/plain", "text/markdown".
 * @param sizeBytes       File size in bytes (enforced ≤ 50 MB before insert).
 * @param totalChunks     Number of chunks produced after successful ingestion; 0
 *                        while status is pending/processing.
 * @param ingestionStatus Pipeline state: "pending" | "processing" | "ready" | "failed".
 * @param failureStage    Which stage failed: "extraction" | "chunking" | "embedding",
 *                        null when [ingestionStatus] is not "failed".
 * @param createdAt       Epoch millis when the document was first submitted.
 */
@Entity(
    tableName = "on_device_documents",
    indices = [Index(value = ["userId"])]
)
data class OnDeviceDocumentEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val totalChunks: Int = 0,
    val ingestionStatus: String, // "pending" | "processing" | "ready" | "failed"
    val failureStage: String? = null,
    val createdAt: Long
)

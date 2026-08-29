/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : OnDeviceChunkEntity.kt
 * Purpose    : Room entity representing a single text chunk produced by the
 *              on-device document ingestion pipeline.  Each chunk stores its
 *              raw text content and the pre-computed embedding as a binary
 *              blob so cosine similarity search can be performed entirely
 *              in-process without a network call.
 *
 * Architecture Layer : Core-Database — persistence layer.
 *                      LocalVectorIndex (core-ai) reads/writes rows through
 *                      OnDeviceChunkDao; feature modules never touch this
 *                      entity directly.
 *
 * Dependencies       : Room, DatabaseConverters (FloatArray↔ByteArray)
 *
 * Design Decision    : embeddingBlob is typed as FloatArray in Kotlin and
 *                      stored as a ByteArray BLOB via DatabaseConverters.
 *                      Keeping it as FloatArray in the entity means cosine
 *                      similarity code works directly on the retrieved value
 *                      with no extra conversion step in the calling layer.
 *                      pageNumber is nullable — plain-text and Markdown files
 *                      do not have page boundaries; only PDF ingestion populates it.
 * ============================================================
 */
package com.aiassistant.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One chunk of a document together with its embedding vector.
 *
 * @param id               UUID, primary key.
 * @param userId           Owner of the parent document — enforced at SQL layer.
 * @param documentId       FK to [OnDeviceDocumentEntity.id] (CASCADE DELETE).
 * @param documentName     Denormalised file name for citation display without a JOIN.
 * @param chunkIndex       Zero-based position of this chunk within the document.
 * @param pageNumber       PDF page number (1-based); null for TXT / Markdown.
 * @param startCharOffset  Start character offset within the full document text.
 * @param endCharOffset    End character offset (exclusive) within the full text.
 * @param content          The raw text of this chunk (displayed in citation UI).
 * @param embeddingBlob    Serialised FloatArray (little-endian IEEE 754 float32).
 *                         Converted transparently by [DatabaseConverters.fromFloatArray]
 *                         / [DatabaseConverters.toFloatArray].
 * @param createdAt        Epoch millis of chunk creation.
 */
@Entity(
    tableName = "on_device_chunks",
    foreignKeys = [
        ForeignKey(
            entity = OnDeviceDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId"]),
        Index(value = ["documentId"])
    ]
)
data class OnDeviceChunkEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val documentId: String,
    val documentName: String,
    val chunkIndex: Int,
    val pageNumber: Int?,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val content: String,
    // Stored as BLOB via DatabaseConverters.fromFloatArray / toFloatArray
    val embeddingBlob: FloatArray,
    val createdAt: Long
) {
    // FloatArray does not implement structural equals/hashCode by default.
    // Override them so tests and Room's conflict detection work correctly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OnDeviceChunkEntity) return false
        return id == other.id &&
            userId == other.userId &&
            documentId == other.documentId &&
            documentName == other.documentName &&
            chunkIndex == other.chunkIndex &&
            pageNumber == other.pageNumber &&
            startCharOffset == other.startCharOffset &&
            endCharOffset == other.endCharOffset &&
            content == other.content &&
            embeddingBlob.contentEquals(other.embeddingBlob) &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + documentName.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + (pageNumber ?: 0)
        result = 31 * result + startCharOffset
        result = 31 * result + endCharOffset
        result = 31 * result + content.hashCode()
        result = 31 * result + embeddingBlob.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

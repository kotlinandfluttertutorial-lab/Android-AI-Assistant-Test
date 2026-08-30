/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : OnDeviceDocumentDao.kt
 * Purpose    : Room DAO for on-device RAG document lifecycle management.
 *              Provides the persistence operations needed by
 *              OnDeviceDocumentRepositoryImpl (data module) to track which
 *              documents have been ingested into the local vector index.
 *
 * Architecture Layer : Core-Database — data access layer.
 *                      Only the data module's repository implementations
 *                      may inject this DAO; feature modules access document
 *                      data through domain repository interfaces.
 *
 * Dependencies       : Room, OnDeviceDocumentEntity, kotlinx.coroutines.flow
 *
 * Design Decision    : updateStatus() uses a targeted @Query rather than a
 *                      full @Update so callers only write the three mutable
 *                      columns (ingestionStatus, failureStage, totalChunks)
 *                      without touching immutable metadata like fileName or
 *                      sizeBytes.  This avoids accidental overwrites if the
 *                      object is reconstructed mid-flight.
 * ============================================================
 */
package com.aiassistant.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiassistant.core.database.entity.OnDeviceDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OnDeviceDocumentDao {

    /**
     * Inserts a new document record.  REPLACE strategy handles the edge case
     * where a user re-submits the same file before the previous ingestion
     * completes — the new row wins.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: OnDeviceDocumentEntity)

    /**
     * Returns a live [Flow] of all documents belonging to [userId], ordered
     * newest-first.  Emits a new list whenever any row for this user changes.
     */
    @Query("SELECT * FROM on_device_documents WHERE userId = :userId ORDER BY createdAt DESC")
    fun getDocuments(userId: String): Flow<List<OnDeviceDocumentEntity>>

    /**
     * Updates the three mutable ingestion-state columns for a document.
     *
     * @param id             Target document id.
     * @param status         New status: "pending" | "processing" | "ready" | "failed".
     * @param failureStage   Which pipeline stage failed, or null when not "failed".
     * @param totalChunks    Final chunk count on "ready"; 0 while still processing.
     */
    @Query(
        """
        UPDATE on_device_documents
        SET ingestionStatus = :status,
            failureStage    = :failureStage,
            totalChunks     = :totalChunks
        WHERE id = :id
        """
    )
    suspend fun updateStatus(id: String, status: String, failureStage: String?, totalChunks: Int)

    /**
     * Hard-deletes a document row.  The CASCADE foreign key on
     * [OnDeviceChunkEntity.documentId] removes all associated chunks
     * automatically in the same transaction.
     *
     * @param id     Document to delete.
     * @param userId Scoping guard — only deletes a row owned by this user,
     *               preventing cross-user deletion bugs.
     */
    @Query("DELETE FROM on_device_documents WHERE id = :id AND userId = :userId")
    suspend fun delete(id: String, userId: String)
}

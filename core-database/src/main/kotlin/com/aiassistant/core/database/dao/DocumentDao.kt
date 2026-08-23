/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : DocumentDao.kt
 * Purpose    : Room DAO interface defining SQL queries for Document entities
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
 * File       : DocumentDao.kt
 * Purpose    : Room DAO interface defining SQL queries for Document entities
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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aiassistant.core.database.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocuments(docs: List<DocumentEntity>)

    @Query("SELECT * FROM documents WHERE userId = :userId ORDER BY createdAt DESC")
    fun getDocumentsByUser(userId: String): Flow<List<DocumentEntity>>

    @Update
    suspend fun updateDocument(doc: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Query("SELECT * FROM documents WHERE id = :id")
    fun getDocumentById(id: String): Flow<DocumentEntity?>
}

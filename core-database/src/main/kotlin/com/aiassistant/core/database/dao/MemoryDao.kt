/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : MemoryDao.kt
 * Purpose    : Room DAO interface defining SQL queries for Memory entities
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
 * File       : MemoryDao.kt
 * Purpose    : Room DAO interface defining SQL queries for Memory entities
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
import com.aiassistant.core.database.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE userId = :userId ORDER BY createdAt DESC")
    fun getMemoriesByUser(userId: String): Flow<List<MemoryEntity>>

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: String)
}

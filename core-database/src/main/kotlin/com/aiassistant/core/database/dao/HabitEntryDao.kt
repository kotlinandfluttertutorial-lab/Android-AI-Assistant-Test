/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : HabitEntryDao.kt
 * Purpose    : Room DAO interface defining SQL queries for HabitEntry entities
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
 * File       : HabitEntryDao.kt
 * Purpose    : Room DAO interface defining SQL queries for HabitEntry entities
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
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiassistant.core.database.entity.HabitEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitEntryDao {

    @Query("SELECT * FROM habit_entries WHERE habitId = :habitId ORDER BY completedAt DESC")
    fun getEntriesForHabit(habitId: String): Flow<List<HabitEntryEntity>>

    @Query("SELECT * FROM habit_entries WHERE userId = :userId AND completedAt >= :sinceMs ORDER BY completedAt DESC")
    fun getEntriesSince(userId: String, sinceMs: Long): Flow<List<HabitEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HabitEntryEntity)

    @Delete
    suspend fun delete(entry: HabitEntryEntity)
}

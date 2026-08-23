/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : HabitDefinitionDao.kt
 * Purpose    : Room DAO interface defining SQL queries for HabitDefinition entities
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
 * File       : HabitDefinitionDao.kt
 * Purpose    : Room DAO interface defining SQL queries for HabitDefinition entities
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
import androidx.room.Update
import com.aiassistant.core.database.entity.HabitDefinitionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDefinitionDao {

    @Query("SELECT * FROM habit_definitions WHERE userId = :userId ORDER BY createdAt ASC")
    fun getAll(userId: String): Flow<List<HabitDefinitionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(habit: HabitDefinitionEntity)

    @Update
    suspend fun update(habit: HabitDefinitionEntity)

    @Delete
    suspend fun delete(habit: HabitDefinitionEntity)
}

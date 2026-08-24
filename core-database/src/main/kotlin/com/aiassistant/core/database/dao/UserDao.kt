/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : UserDao.kt
 * Purpose    : Room DAO interface defining SQL queries for User entities
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
 * File       : UserDao.kt
 * Purpose    : Room DAO interface defining SQL queries for User entities
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
import com.aiassistant.core.database.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :id")
    fun getUserById(id: String): Flow<UserEntity?>

    /**
     * Returns a [Flow] emitting the first (and typically only) user in the table.
     * Used by [UserRepositoryImpl] to observe the active user profile.
     */
    @Query("SELECT * FROM users LIMIT 1")
    fun getAllUsers(): Flow<UserEntity?>

    /**
     * Synchronous query returning the first (and typically only) user in the table.
     * Used for non-Flow contexts such as update operations.
     *
     * @return The first [UserEntity], or null if no user is stored.
     */
    @Query("SELECT * FROM users LIMIT 1")
    suspend fun getFirstUser(): UserEntity?

    @Update
    suspend fun updateUser(user: UserEntity)
}

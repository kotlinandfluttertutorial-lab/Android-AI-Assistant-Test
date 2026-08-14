/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : TodoItemDao.kt
 * Purpose    : Room DAO interface defining SQL queries for TodoItem entities
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
 * File       : TodoItemDao.kt
 * Purpose    : Room DAO interface defining SQL queries for TodoItem entities
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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aiassistant.core.database.entity.TodoItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoItemDao {

    @Query("SELECT * FROM todo_items WHERE userId = :userId ORDER BY createdAt DESC")
    fun getTodos(userId: String): PagingSource<Int, TodoItemEntity>

    @Query("SELECT * FROM todo_items WHERE userId = :userId AND isCompleted = :isCompleted ORDER BY dueDate ASC")
    fun getTodosByCompletion(userId: String, isCompleted: Boolean): Flow<List<TodoItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(todo: TodoItemEntity)

    @Update
    suspend fun update(todo: TodoItemEntity)

    @Delete
    suspend fun delete(todo: TodoItemEntity)

    @Query("SELECT * FROM todo_items WHERE syncStatus = 'pending'")
    suspend fun getPendingTodos(): List<TodoItemEntity>
}
